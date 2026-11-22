package com.pokrag.serverrules.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
import com.pokrag.serverrules.complaint.CitationChange;
import com.pokrag.serverrules.complaint.CitationType;
import com.pokrag.serverrules.complaint.CitedSanction;
import com.pokrag.serverrules.complaint.Complaint;
import com.pokrag.serverrules.complaint.ComplaintStatus;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathMember;
import com.pokrag.serverrules.oath.OathStatus;
import com.pokrag.serverrules.rule.ChangeType;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.RuleChange;
import com.pokrag.serverrules.sanction.AppliedSanction;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Acces a la base SQLite du plugin.
 *
 * - Une seule connexion, utilisee par un unique thread dedie : aucune requete
 *   ne s'execute sur le thread principal du serveur.
 * - Tables :
 *   players      : UUID du joueur et derniere version de regles vue ;
 *   rule_changes : journal des ajouts / modifications / suppressions ;
 *   rule_snapshot: etat connu des regles, pour detecter les modifications manuelles.
 */
public final class Database {

    /** Etat connu d'une regle (pour detecter une modification faite a la main). */
    public record SnapshotRow(int id, String revision, int number, String name) {
    }

    /** Un changement a enregistrer dans le journal. */
    public record Change(ChangeType type, int ruleNumber, String ruleName) {
    }

    private final ServerRulesPlugin plugin;
    private final ExecutorService executor;
    private Connection connection;

    public Database(ServerRulesPlugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ServerRules-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Ouvre la base et cree les tables si besoin. Appele au demarrage du plugin. */
    public void open() throws SQLException {
        File file = new File(plugin.getDataFolder(), "serverrules.db");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Pilote SQLite introuvable dans le JAR.", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS players ("
                    + "uuid TEXT PRIMARY KEY, last_seq INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rule_changes ("
                    + "seq INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, "
                    + "rule_number INTEGER NOT NULL, rule_name TEXT NOT NULL, date INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rule_snapshot ("
                    + "id INTEGER PRIMARY KEY, revision TEXT NOT NULL, "
                    + "rule_number INTEGER NOT NULL, rule_name TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS complaints ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "plaintiff TEXT NOT NULL, plaintiff_name TEXT NOT NULL, "
                    + "accused TEXT NOT NULL, accused_name TEXT NOT NULL, "
                    + "citation_type TEXT NOT NULL, citation_ref_id INTEGER NOT NULL, "
                    + "citation_number INTEGER NOT NULL, citation_name TEXT NOT NULL, "
                    + "sanction_kind TEXT NOT NULL, sanction_amount REAL NOT NULL, "
                    + "sanction_duration INTEGER NOT NULL, custom_text TEXT NOT NULL DEFAULT '', "
                    + "details TEXT NOT NULL, date INTEGER NOT NULL, status TEXT NOT NULL, "
                    + "citation_sanctions TEXT NOT NULL DEFAULT '')");
            // Migration 1.0 -> 1.0a : ajout de la colonne des sanctions multiples.
            ensureColumn("complaints", "citation_sanctions", "TEXT NOT NULL DEFAULT ''");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS complaint_citation_changes ("
                    + "seq INTEGER PRIMARY KEY AUTOINCREMENT, complaint_id INTEGER NOT NULL, "
                    + "old_number INTEGER NOT NULL, old_name TEXT NOT NULL, "
                    + "new_number INTEGER NOT NULL, new_name TEXT NOT NULL, "
                    + "judge_name TEXT NOT NULL, date INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sanctions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, complaint_id INTEGER NOT NULL, "
                    + "rule_ref_id INTEGER NOT NULL, rule_number INTEGER NOT NULL, rule_name TEXT NOT NULL, "
                    + "kind TEXT NOT NULL, amount REAL NOT NULL, duration INTEGER NOT NULL, "
                    + "judge_name TEXT NOT NULL, date INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS oaths ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "founder TEXT NOT NULL, founder_name TEXT NOT NULL, promise TEXT NOT NULL, "
                    + "duration INTEGER NOT NULL, sanction_kind TEXT NOT NULL, amount REAL NOT NULL, "
                    + "sanction_duration INTEGER NOT NULL, custom_text TEXT NOT NULL DEFAULT '', "
                    + "code TEXT NOT NULL, created_at INTEGER NOT NULL, code_expires_at INTEGER NOT NULL, "
                    + "activated_at INTEGER NOT NULL, status TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS oath_members ("
                    + "oath_id INTEGER NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, "
                    + "joined_at INTEGER NOT NULL, PRIMARY KEY (oath_id, uuid))");
        }
    }

    /** Ferme la base et arrete le thread dedie. */
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Fermeture de la base SQLite : " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- ecritures

    /** Enregistre une liste de changements et renvoie les entrees creees (avec leur numero de sequence). */
    public CompletableFuture<List<RuleChange>> appendChanges(List<Change> changes) {
        return supply(() -> {
            List<RuleChange> created = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rule_changes(type, rule_number, rule_name, date) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                for (Change change : changes) {
                    ps.setString(1, change.type().name());
                    ps.setInt(2, change.ruleNumber());
                    ps.setString(3, change.ruleName());
                    ps.setLong(4, now);
                    ps.executeUpdate();
                    long seq = 0;
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            seq = keys.getLong(1);
                        }
                    }
                    created.add(new RuleChange(seq, change.type(), change.ruleNumber(), change.ruleName(), now));
                }
            }
            return created;
        });
    }

    /** Remplace l'etat connu des regles par la liste fournie. */
    public CompletableFuture<Void> saveSnapshot(List<Rule> rules) {
        return execute(() -> {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("DELETE FROM rule_snapshot");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rule_snapshot(id, revision, rule_number, rule_name) VALUES(?,?,?,?)")) {
                int number = 1;
                for (Rule rule : rules) {
                    ps.setInt(1, rule.id());
                    ps.setString(2, rule.revision());
                    ps.setInt(3, number++);
                    ps.setString(4, rule.name());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** Enregistre la derniere version de regles vue par un joueur. */
    public CompletableFuture<Void> setLastSeq(UUID uuid, long seq) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO players(uuid, last_seq) VALUES(?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET last_seq = excluded.last_seq")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, seq);
                ps.executeUpdate();
            }
        });
    }

    // ---------------------------------------------------------------- lectures

    /** @return le plus grand numero de sequence du journal, ou 0 si le journal est vide. */
    public CompletableFuture<Long> maxSeq() {
        return supply(() -> {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM rule_changes")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    /** @return la derniere version de regles vue par un joueur, vide s'il n'a jamais ete vu. */
    public CompletableFuture<OptionalLong> lastSeq(UUID uuid) {
        return supply(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT last_seq FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
                }
            }
        });
    }

    /** @return les changements posterieurs a la version donnee, du plus ancien au plus recent. */
    public CompletableFuture<List<RuleChange>> changesAfter(long seq) {
        return supply(() -> {
            List<RuleChange> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT seq, type, rule_number, rule_name, date FROM rule_changes "
                            + "WHERE seq > ? ORDER BY seq")) {
                ps.setLong(1, seq);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new RuleChange(
                                rs.getLong(1),
                                ChangeType.valueOf(rs.getString(2)),
                                rs.getInt(3),
                                rs.getString(4),
                                rs.getLong(5)));
                    }
                }
            }
            return list;
        });
    }

    /** @return l'etat connu des regles, indexe par identifiant interne. */
    public CompletableFuture<Map<Integer, SnapshotRow>> loadSnapshot() {
        return supply(() -> {
            Map<Integer, SnapshotRow> map = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, revision, rule_number, rule_name FROM rule_snapshot")) {
                while (rs.next()) {
                    map.put(rs.getInt(1),
                            new SnapshotRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                }
            }
            return map;
        });
    }

    // ------------------------------------------------------------- plaintes

    /** Enregistre une plainte et renvoie son identifiant attribue. */
    public CompletableFuture<Long> insertComplaint(Complaint c) {
        return supply(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO complaints(plaintiff, plaintiff_name, accused, accused_name, "
                            + "citation_type, citation_ref_id, citation_number, citation_name, "
                            + "sanction_kind, sanction_amount, sanction_duration, custom_text, "
                            + "details, date, status, citation_sanctions) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                CitedSanction first = firstOf(c.citation().sanctions());
                ps.setString(1, c.plaintiff().toString());
                ps.setString(2, c.plaintiffName());
                ps.setString(3, c.accused().toString());
                ps.setString(4, c.accusedName());
                ps.setString(5, c.citation().type().name());
                ps.setInt(6, c.citation().refId());
                ps.setInt(7, c.citation().number());
                ps.setString(8, c.citation().name());
                // Colonnes historiques (1.0) : resume de la premiere sanction, conservees pour compatibilite.
                ps.setString(9, first == null ? SanctionKind.FINE.name() : first.kind().name());
                ps.setDouble(10, first == null ? 0 : first.amount());
                ps.setLong(11, first == null ? 0 : first.durationSeconds());
                ps.setString(12, first == null ? "" : first.customText());
                ps.setString(13, c.details());
                ps.setLong(14, c.date());
                ps.setString(15, c.status().name());
                ps.setString(16, encodeSanctions(c.citation().sanctions()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        });
    }

    /** @return les plaintes, filtrees par statut si fourni, de la plus ancienne a la plus recente. */
    public CompletableFuture<List<Complaint>> listComplaints(ComplaintStatus status) {
        return supply(() -> {
            List<Complaint> list = new ArrayList<>();
            String sql = "SELECT * FROM complaints"
                    + (status == null ? "" : " WHERE status = ?") + " ORDER BY id";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                if (status != null) {
                    ps.setString(1, status.name());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(readComplaint(rs));
                    }
                }
            }
            return list;
        });
    }

    /** @return la plainte d'identifiant donne. */
    public CompletableFuture<Optional<Complaint>> findComplaint(long id) {
        return supply(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM complaints WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readComplaint(rs)) : Optional.empty();
                }
            }
        });
    }

    /** @return la date (epoch millis) de la derniere plainte deposee par un joueur. */
    public CompletableFuture<OptionalLong> lastComplaintDate(UUID plaintiff) {
        return supply(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COALESCE(MAX(date), 0) FROM complaints WHERE plaintiff = ?")) {
                ps.setString(1, plaintiff.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    long value = rs.next() ? rs.getLong(1) : 0L;
                    return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
                }
            }
        });
    }

    /** Remplace la reference citee par une plainte. */
    public CompletableFuture<Void> updateComplaintCitation(long id, Citation citation) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE complaints SET citation_type = ?, citation_ref_id = ?, citation_number = ?, "
                            + "citation_name = ?, sanction_kind = ?, sanction_amount = ?, "
                            + "sanction_duration = ?, custom_text = ?, citation_sanctions = ? WHERE id = ?")) {
                CitedSanction first = firstOf(citation.sanctions());
                ps.setString(1, citation.type().name());
                ps.setInt(2, citation.refId());
                ps.setInt(3, citation.number());
                ps.setString(4, citation.name());
                ps.setString(5, first == null ? SanctionKind.FINE.name() : first.kind().name());
                ps.setDouble(6, first == null ? 0 : first.amount());
                ps.setLong(7, first == null ? 0 : first.durationSeconds());
                ps.setString(8, first == null ? "" : first.customText());
                ps.setString(9, encodeSanctions(citation.sanctions()));
                ps.setLong(10, id);
                ps.executeUpdate();
            }
        });
    }

    /** Change le statut d'une plainte. */
    public CompletableFuture<Void> updateComplaintStatus(long id, ComplaintStatus status) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE complaints SET status = ? WHERE id = ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
    }

    /** Ajoute une entree a la trace des changements de reference citee. */
    public CompletableFuture<Void> appendCitationChange(CitationChange change) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO complaint_citation_changes(complaint_id, old_number, old_name, "
                            + "new_number, new_name, judge_name, date) VALUES(?,?,?,?,?,?,?)")) {
                ps.setLong(1, change.complaintId());
                ps.setInt(2, change.oldNumber());
                ps.setString(3, change.oldName());
                ps.setInt(4, change.newNumber());
                ps.setString(5, change.newName());
                ps.setString(6, change.judgeName());
                ps.setLong(7, change.date());
                ps.executeUpdate();
            }
        });
    }

    /** @return la trace des changements de reference citee d'une plainte. */
    public CompletableFuture<List<CitationChange>> citationChanges(long complaintId) {
        return supply(() -> {
            List<CitationChange> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT complaint_id, old_number, old_name, new_number, new_name, judge_name, date "
                            + "FROM complaint_citation_changes WHERE complaint_id = ? ORDER BY seq")) {
                ps.setLong(1, complaintId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new CitationChange(rs.getLong(1), rs.getInt(2), rs.getString(3),
                                rs.getInt(4), rs.getString(5), rs.getString(6), rs.getLong(7)));
                    }
                }
            }
            return list;
        });
    }

    /** Enregistre une sanction appliquee dans l'historique. */
    public CompletableFuture<Void> appendSanction(AppliedSanction sanction) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sanctions(complaint_id, rule_ref_id, rule_number, rule_name, kind, "
                            + "amount, duration, judge_name, date) VALUES(?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, sanction.complaintId());
                ps.setInt(2, sanction.ruleRefId());
                ps.setInt(3, sanction.ruleNumber());
                ps.setString(4, sanction.ruleName());
                ps.setString(5, sanction.kind().name());
                ps.setDouble(6, sanction.amount());
                ps.setLong(7, sanction.durationSeconds());
                ps.setString(8, sanction.judgeName());
                ps.setLong(9, sanction.date());
                ps.executeUpdate();
            }
        });
    }

    // -------------------------------------------------------------- serments

    /** Enregistre un serment et son fondateur ; renvoie l'identifiant attribue. */
    public CompletableFuture<Long> insertOath(Oath oath, OathMember founder) {
        return supply(() -> {
            long id;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO oaths(founder, founder_name, promise, duration, sanction_kind, amount, "
                            + "sanction_duration, custom_text, code, created_at, code_expires_at, "
                            + "activated_at, status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, oath.founder().toString());
                ps.setString(2, oath.founderName());
                ps.setString(3, oath.promise());
                ps.setLong(4, oath.durationSeconds());
                ps.setString(5, oath.sanctionKind().name());
                ps.setDouble(6, oath.amount());
                ps.setLong(7, oath.sanctionDurationSeconds());
                ps.setString(8, oath.customText());
                ps.setString(9, oath.code());
                ps.setLong(10, oath.createdAt());
                ps.setLong(11, oath.codeExpiresAt());
                ps.setLong(12, oath.activatedAt());
                ps.setString(13, oath.status().name());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0L;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO oath_members(oath_id, uuid, name, joined_at) VALUES(?,?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, founder.uuid().toString());
                ps.setString(3, founder.name());
                ps.setLong(4, founder.joinedAt());
                ps.executeUpdate();
            }
            return id;
        });
    }

    /** Change le statut (et la date d'activation) d'un serment. */
    public CompletableFuture<Void> updateOathStatus(long id, OathStatus status, long activatedAt) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE oaths SET status = ?, activated_at = ? WHERE id = ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, activatedAt);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        });
    }

    /** Ajoute une partie a un serment. */
    public CompletableFuture<Void> addOathMember(long oathId, OathMember member) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO oath_members(oath_id, uuid, name, joined_at) VALUES(?,?,?,?)")) {
                ps.setLong(1, oathId);
                ps.setString(2, member.uuid().toString());
                ps.setString(3, member.name());
                ps.setLong(4, member.joinedAt());
                ps.executeUpdate();
            }
        });
    }

    /** @return tous les serments, du plus ancien au plus recent. */
    public CompletableFuture<List<Oath>> listOaths() {
        return supply(() -> {
            List<Oath> list = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM oaths ORDER BY id")) {
                while (rs.next()) {
                    list.add(readOath(rs));
                }
            }
            return list;
        });
    }

    /** @return les parties de tous les serments, indexees par identifiant de serment. */
    public CompletableFuture<Map<Long, List<OathMember>>> loadOathMembers() {
        return supply(() -> {
            Map<Long, List<OathMember>> map = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT oath_id, uuid, name, joined_at FROM oath_members ORDER BY oath_id, joined_at")) {
                while (rs.next()) {
                    map.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>())
                            .add(new OathMember(UUID.fromString(rs.getString(2)),
                                    rs.getString(3), rs.getLong(4)));
                }
            }
            return map;
        });
    }

    private Oath readOath(ResultSet rs) throws SQLException {
        return new Oath(
                rs.getLong("id"),
                UUID.fromString(rs.getString("founder")),
                rs.getString("founder_name"),
                rs.getString("promise"),
                rs.getLong("duration"),
                SanctionKind.valueOf(rs.getString("sanction_kind")),
                rs.getDouble("amount"),
                rs.getLong("sanction_duration"),
                rs.getString("custom_text"),
                rs.getString("code"),
                rs.getLong("created_at"),
                rs.getLong("code_expires_at"),
                rs.getLong("activated_at"),
                OathStatus.valueOf(rs.getString("status")));
    }

    private Complaint readComplaint(ResultSet rs) throws SQLException {
        List<CitedSanction> sanctions = decodeSanctions(rs.getString("citation_sanctions"));
        if (sanctions.isEmpty()) {
            // Donnees 1.0 : une seule sanction dans les colonnes historiques.
            sanctions = List.of(new CitedSanction(
                    SanctionKind.valueOf(rs.getString("sanction_kind")),
                    rs.getDouble("sanction_amount"),
                    rs.getLong("sanction_duration"),
                    rs.getString("custom_text")));
        }
        Citation citation = new Citation(
                CitationType.valueOf(rs.getString("citation_type")),
                rs.getInt("citation_ref_id"),
                rs.getInt("citation_number"),
                rs.getString("citation_name"),
                sanctions);
        return new Complaint(
                rs.getLong("id"),
                UUID.fromString(rs.getString("plaintiff")),
                rs.getString("plaintiff_name"),
                UUID.fromString(rs.getString("accused")),
                rs.getString("accused_name"),
                citation,
                rs.getString("details"),
                rs.getLong("date"),
                ComplaintStatus.valueOf(rs.getString("status")));
    }

    // ------------------------------------------------------------- migration

    /** Ajoute une colonne a une table si elle n'existe pas encore (migration d'une base existante). */
    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    /** @return la premiere sanction d'une liste, ou null si elle est vide. */
    private static CitedSanction firstOf(List<CitedSanction> sanctions) {
        return sanctions.isEmpty() ? null : sanctions.get(0);
    }

    /** Serialise une liste de sanctions figees en JSON (colonne {@code citation_sanctions}). */
    private static String encodeSanctions(List<CitedSanction> sanctions) {
        JsonArray array = new JsonArray();
        for (CitedSanction sanction : sanctions) {
            JsonObject object = new JsonObject();
            object.addProperty("kind", sanction.kind().name());
            object.addProperty("amount", sanction.amount());
            object.addProperty("duration", sanction.durationSeconds());
            object.addProperty("text", sanction.customText());
            array.add(object);
        }
        return array.toString();
    }

    /** Relit une liste de sanctions figees depuis son JSON ; liste vide si le contenu est illisible. */
    private static List<CitedSanction> decodeSanctions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<CitedSanction> list = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject object = element.getAsJsonObject();
                list.add(new CitedSanction(
                        SanctionKind.valueOf(object.get("kind").getAsString()),
                        object.get("amount").getAsDouble(),
                        object.get("duration").getAsLong(),
                        object.has("text") && !object.get("text").isJsonNull()
                                ? object.get("text").getAsString() : ""));
            }
            return list;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- interne

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private CompletableFuture<Void> execute(SqlRunnable task) {
        return supply(() -> {
            task.run();
            return null;
        });
    }
}
