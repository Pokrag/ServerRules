# Feuille de route : plugin Paper `ServerRules`

## Objectif

Un plugin qui :

1. **Informe** les joueurs des règles du serveur dans le chat (sans acceptation, sans pop-up, sans blocage).
2. Permet aux joueurs de **déposer une plainte** contre un autre joueur.
3. Donne à un rôle **Juge** (une permission) le pouvoir de traiter les plaintes et d'appliquer les sanctions prévues par les règles.
4. Permet à **deux joueurs ou plus** de conclure des **serments** (promesse avec sanction), dont le non-respect peut être signalé par une plainte.

## Stack technique

- **Cible prioritaire : Paper 26.3** (Minecraft 26.3)
- **Java 25** (requis par Paper 26.x)
- API Paper : `io.papermc.paper:paper-api:26.3.build.+` en `compileOnly`, dépôt `https://repo.papermc.io/repository/maven-public/`. L'ancien suffixe `-R0.1-SNAPSHOT` n'existe plus avec le nouveau schéma de version.
- Trae doit vérifier dans la documentation Paper actuelle la syntaxe exacte de la dépendance, la valeur d'`api-version` du descripteur du plugin et la méthode recommandée pour enregistrer les commandes.
- Aucun code déprécié
- D'autres versions de Paper pourront être supportées ensuite (voir Phase 13), sans jamais gêner la cible 26.3
- Outil de build : **au choix de Trae** (Maven ou Gradle)
- SQLite (suivi des joueurs et des plaintes)
- MiniMessage pour les messages
- Dépendance **Vault** (VaultAPI), obligatoire, pour les amendes
- Plugin d'économie : **EssentialsX**, obligatoire (branché à Vault)
- Attention : EssentialsX 2.22.0 annonce un support officiel de Paper 26.1.2, mais le support de 26.3 n'est pas confirmé dans ses notes de version. Utiliser la dernière version disponible (ou un build de développement 2.22.1) et vérifier que EssentialsX et Vault fonctionnent sur 26.3 avant de tester le plugin. Le support de Vault sur 26.3 n'a pas été vérifié non plus.

---

## Phase 1 : Base du projet

- Structure du projet (Maven ou Gradle, au choix de Trae), `plugin.yml`, classe principale.
- `plugin.yml` : `depend: [Vault, Essentials]`.
- Au démarrage, vérifier qu'EssentialsX est présent et qu'il est bien le fournisseur d'économie branché à Vault. Sinon : erreur claire dans la console et désactivation du plugin.
- `config.yml` contenant :
  - la liste des règles
  - la langue par défaut et les options de langue (voir « Langues » ci-dessous)
  - le délai d'affichage après connexion (en secondes)
  - le nom et les alias des commandes (voir Phase 5)
  - les limites de modulation des sanctions (voir Phase 6)

### Structure d'une règle

Chaque règle possède :

- un **numéro** : sa position dans la liste (il change quand on déplace des règles)
- un **identifiant interne stable**, jamais réutilisé, utilisé par la base de données, les plaintes et le suivi des changements
- un **nom**
- une **description**
- une **sanction prévue**, composée de :
  - un **type** : `TEMP_BAN` (ban temporaire) ou `FINE` (amende monétaire)
  - une **valeur** : durée pour un ban, montant pour une amende

> Le type « service rendu » est **supprimé**. Seuls `TEMP_BAN` et `FINE` existent.

```yaml
rules:
  1:
    id: 1
    name: "Respect"
    description: "Aucune insulte ni harcèlement."
    sanction:
      type: FINE
      amount: 5.0
  2:
    id: 2
    name: "Triche"
    description: "Aucun hack ni exploit."
    sanction:
      type: TEMP_BAN
      duration: "3d"
```

### Langues

- Tous les textes affichés (chat, titres, notifications, noms et descriptions des objets des GUI, boutons) sont dans des **fichiers de langue** `lang/<code>.yml`, en **MiniMessage**. Aucun texte en dur dans le code. Les admins peuvent les modifier librement.
- Langue par défaut : **anglais** (`en.yml`), fichier de référence complet.
- Fournir **le plus de langues possible** en plus de l'anglais, au minimum : français (`fr`), espagnol (`es`), allemand (`de`), italien (`it`), portugais (`pt`), néerlandais (`nl`), polonais (`pl`), russe (`ru`), turc (`tr`), japonais (`ja`), coréen (`ko`), chinois simplifié (`zh_CN`). Trae génère les traductions et les signale comme générées automatiquement, à relire.
- Option `language.per-player` : si activée, chaque joueur voit les messages dans la langue de son client Minecraft quand un fichier existe, sinon dans la langue par défaut (`language.default`).
- Une clé absente d'un fichier de langue retombe sur l'anglais, sans erreur.
- Le contenu des règles (nom, description) est écrit par les admins dans `config.yml` et les textes des plaintes et des serments sont écrits par les joueurs : ils ne sont pas traduits.
- `/rules reload` recharge aussi les fichiers de langue.

## Phase 2 : Affichage des règles

- Les règles s'affichent en **tableau** dans le chat : n°, nom, description, sanction.
- Le montant d'une amende est formaté avec la monnaie du serveur (via Vault). La durée d'un ban est affichée lisiblement.
- **Première connexion** : toutes les règles sont affichées, après un délai configurable.
- **Connexions suivantes** : un message dans le chat juste après la connexion, uniquement si des règles ont été ajoutées, modifiées ou supprimées depuis la dernière visite du joueur.

## Phase 3 : Suivi des changements (SQLite)

- Table des joueurs : UUID et dernière version de règles vue.
- Chaque règle a une révision ou un hash, pour détecter aussi les modifications faites à la main dans la config.
- Quand une règle est ajoutée, modifiée ou **supprimée**, les joueurs **déjà en ligne sont notifiés immédiatement**.
- **Déplacer** une règle (changer son ordre) **ne déclenche aucune notification** et ne compte pas comme une modification : l'ordre n'entre pas dans la révision (hash) d'une règle.
- Les suppressions sont enregistrées dans un journal des changements (SQLite) : numéro et nom de la règle supprimée, date. Ainsi, un joueur hors ligne est informé de la suppression à sa prochaine connexion, comme pour un ajout ou une modification.
- Requêtes SQLite asynchrones (ne jamais bloquer le thread principal).

## Phase 4 : Commandes joueurs (règles)

| Commande | Effet |
|---|---|
| `/rules` | Affiche toutes les règles |
| `/rules <n°>` | Affiche une règle précise |
| `/rules new` | Revoir les nouveautés (règles ajoutées, modifiées ou supprimées) |

## Phase 5 : Système de plaintes

### Dépôt d'une plainte (joueur)

- `/plaint` tapé seul **ouvre directement la GUI** (mes plaintes + bouton « Nouvelle plainte », voir Phase 11).
- Commande en une ligne : `/plaint <joueur> <n° de règle> <détails>` (alias : `/plainte`, `/complaint`, `/complaints`)
- La plainte **doit citer une règle précise** : le numéro est obligatoire et doit correspondre à une règle existante (sinon message d'erreur). Le numéro saisi est converti en identifiant interne stable à l'enregistrement de la plainte. Autocomplétion (tab) des joueurs et des numéros de règles.
- Le nom de la commande et ses alias sont **configurables** dans `config.yml`. Nom principal retenu : `plaint`.
- Exemple : `/plaint Steve 3 Il a détruit ma maison au spawn`
- Une plainte peut aussi porter sur un **serment non respecté** : au lieu d'un numéro de règle, le plaignant cite l'identifiant du serment (voir Phase 9). Le plugin sait alors quelle sanction appliquer.
- Chaque plainte est enregistrée en SQLite avec : identifiant, plaignant, joueur accusé, **règle citée**, détails, date, statut.
- Statuts possibles : `EN_ATTENTE`, `TRAITÉE`, `CLASSÉE_SANS_SUITE`.
- **Anti-spam** : délai minimum entre deux plaintes d'un même joueur (`complaint.cooldown-seconds`, 60 par défaut, configurable). Un message indique le temps restant. Il s'applique à la commande et à la GUI.
- Le plaignant reçoit une confirmation d'envoi.
- Permission : `serverrules.complaint` (donnée à tous par défaut).

### Réception (juge)

- Les joueurs ayant la permission `serverrules.judge` (ou `serverrules.superjudge`) reçoivent les plaintes.
- Si un juge est en ligne, il est notifié immédiatement. Sinon, il voit les plaintes en attente à sa prochaine connexion.
- Un juge n'est ni notifié ni ne voit les plaintes dans lesquelles il est impliqué (voir « Conflit d'intérêts » en Phase 6).

## Phase 6 : Traitement des plaintes (juge)

- La commande `/judge` (configurable) **ouvre directement la GUI des plaintes** (voir Phase 11). Ses sous-commandes (Phase 10) donnent accès aux mêmes actions en texte.
- Un juge gère toutes les plaintes auxquelles il a accès, sauf celles où il est impliqué (voir ci-dessous). Fonctions :
  - lister les plaintes (filtrables par statut)
  - consulter le détail d'une plainte (dont la règle citée et sa sanction prévue)
  - appliquer une sanction
  - classer la plainte sans suite (aucune sanction)
  - **changer la règle citée** par le plaignant (réservé aux superjuges, voir ci-dessous)
- Le juge contacte les joueurs de son côté (messages privés, etc.). Le plugin n'a pas à gérer cette partie.

### Rôle de superjuge

- Le **superjuge** est un second rôle, géré par la permission `serverrules.superjudge`.
- Il possède tous les pouvoirs du juge, plus deux pouvoirs supplémentaires :
  - **changer la règle citée dans une plainte** (par exemple si le plaignant s'est trompé de numéro) ;
  - **moduler les sanctions dans une fourchette plus large** (10 % à 500 %, voir ci-dessous).
- Un juge simple ne peut pas modifier la règle citée. Il peut seulement appliquer la sanction de cette règle (modulée) ou classer sans suite.
- Quand la règle est changée, la plainte garde une trace : ancienne règle, nouvelle règle, superjuge, date.

### Conflit d'intérêts

- Un juge ou superjuge **ne peut pas traiter un cas dans lequel il est impliqué** : il est impliqué s'il est le **plaignant**, l'**accusé**, ou l'une des **parties du serment** cité.
- Pour un cas où il est impliqué : la plainte n'apparaît pas dans son menu, aucune notification, et toutes les actions (`view`, `sanction`, `dismiss`, `done`, `rule`) sont refusées avec un message clair.
- **Exception** : une personne possédant la permission spécifique `serverrules.conflict.bypass` peut traiter ces cas. Cette permission s'attribue avec LuckPerms (ou n'importe quel plugin de permissions). Le plugin ne dépend pas de LuckPerms.
- Cette vérification est faite côté serveur à chaque action, pas seulement à l'ouverture du menu.

### Application d'une sanction

Le juge peut :

1. Appliquer **la sanction prévue par la règle** telle quelle.
2. Appliquer une sanction **plus forte ou plus faible, du même type**, dans des limites fixées.
3. **Ne rien appliquer** (classement sans suite).

### Limites de modulation

La sanction ajustée doit rester dans une fourchette autour de la valeur prévue par la règle. Exemple de base : pour une amende de 5 €, le juge peut choisir **entre 2 € et 10 €**.

- Les limites sont **configurables** (pourcentage minimum et maximum par rapport à la valeur de la règle), séparément pour le juge et le superjuge.
- **Juge** : minimum **40 %**, maximum **200 %** (valeurs déduites de l'exemple 5 € → 2 € à 10 €).
- **Superjuge** : minimum **10 %**, maximum **500 %** (5 € → 0,50 € à 25 €).
- Le plugin applique les limites correspondant à la permission la plus haute du joueur (superjuge prioritaire sur juge).
- La règle s'applique pareillement à la durée d'un ban temporaire.
- Le type de sanction ne peut pas être changé (une amende reste une amende, un ban reste un ban).
- Ces limites ne s'appliquent **qu'aux sanctions des règles**, pas aux serments (voir Phase 9).

```yaml
language:
  default: en
  per-player: true
complaint:
  cooldown-seconds: 60
gui:
  input-timeout-seconds: 60
rules:
  confirm-timeout-seconds: 30
oath:
  code-length: 4
  code-expiry-seconds: 60
  code-max-failed-attempts: 5
  code-lockout-seconds: 300
  cancel-request-expiry-seconds: 60
  complaint-grace-days: 2
  max-players: 10
judge:
  min-percent: 40
  max-percent: 200
superjudge:
  min-percent: 10
  max-percent: 500
```

## Phase 7 : Intégration des sanctions

### Amende (Vault)

- Classe dédiée `FineService`, avec `applyFine(joueur, montant)` : retire le montant via l'API Economy de Vault.
- **Solde insuffisant : le joueur passe en solde négatif** (l'amende est appliquée en totalité, jamais refusée ni réduite).
- Gérer aussi : erreurs de transaction, joueur hors ligne.
- Attention : l'API Vault ne permet pas de forcer un solde négatif, et la plupart des plugins d'économie refusent un retrait supérieur au solde. Le `FineService` doit donc :
  1. tenter un retrait normal ;
  2. si le plugin d'économie refuse, détecter l'échec et le signaler clairement au juge (message + log console) au lieu de l'ignorer ;
  3. documenter dans le README que le plugin d'économie doit autoriser les soldes négatifs (avec EssentialsX : définir `min-money` sur une valeur négative suffisamment basse dans sa `config.yml`). Le plugin doit vérifier cette configuration au démarrage et avertir l'admin dans la console si les soldes négatifs sont impossibles.

### Ban temporaire

- Classe dédiée `BanService`, avec `applyTempBan(joueur, durée)` : utilise l'API de ban de Paper avec une date d'expiration.
- Si le joueur est en ligne : le kicker avec un message expliquant la raison et la durée.
- Le message de ban mentionne la règle enfreinte.

### Historique

- Chaque sanction appliquée est enregistrée en SQLite : plainte liée, règle, type, valeur appliquée, juge, date.
- La plainte passe au statut `TRAITÉE`.

## Phase 8 : Commandes admin (règles)

Une permission par commande.

| Commande | Permission | Effet |
|---|---|---|
| `/rules add` | `serverrules.add` | Ajouter une règle (avec sa sanction) |
| `/rules edit <n°>` | `serverrules.edit` | Modifier une règle |
| `/rules remove <n°>` | `serverrules.remove` | Supprimer une règle |
| `/rules move <n°> <position>` | `serverrules.move` | Réordonner les règles |
| `/rules reload` | `serverrules.reload` | Recharger la config |

- **Toute modification des règles (ajout, modification, suppression) demande une confirmation.** Le **déplacement** (`/rules move`, boutons monter/descendre) ne demande **aucune confirmation** et ne notifie personne. En commande : le plugin affiche un récapitulatif et attend `/rules confirm` (ou un bouton cliquable) dans les 30 secondes (`rules.confirm-timeout-seconds`). En GUI : menu « Confirmer / Annuler ».
- Les modifications faites en jeu sont écrites dans `config.yml`.
- `/rules remove` déclenche la notification de suppression décrite en Phase 3.
- Que devient une plainte en attente qui cite une règle supprimée ? Elle garde le numéro et le nom de la règle (copie figée), pour que le juge puisse toujours la traiter.
- Le reload relit toute la config et détecte les changements.

## Phase 9 : Système de serments

Un **serment** est une promesse entre **deux joueurs ou plus**, avec une sanction prévue en cas de non-respect et la possibilité de porter plainte.

### Création et code du serment

- Un joueur (le **fondateur**) crée un serment et choisit tous les paramètres :
  - le **texte de la promesse**
  - la **durée** (fixée librement, ex : `7d`)
  - la **sanction prévue** en cas de non-respect
- À la création, le plugin génère un **code** dont la longueur est configurable (`oath.code-length`, **4 caractères par défaut**). Il est composé de lettres majuscules et de chiffres, sans caractères ambigus comme `0`/`O` ou `1`/`I`. Le code est **unique** parmi les serments en attente ou actifs.
- Le code est **fourni au fondateur** : affiché dans le chat avec un bouton cliquable pour le copier, puis visible dans le détail du serment. Le fondateur le transmet aux autres joueurs comme il veut.
- Il n'y a pas d'invitation par nom de joueur : on rejoint un serment uniquement avec son code.

### Rejoindre un serment

- Un joueur clique sur **Rejoindre un serment** (ou tape `/oath join <code>`) et saisit le code dans le chat (insensible à la casse).
- Le plugin affiche un **récapitulatif complet** (fondateur, parties actuelles, promesse, durée, sanction) avec **Accepter** et **Refuser**. Le joueur n'est lié qu'après avoir accepté.
- Le code reste valide **1 minute après la création** (`oath.code-expiry-seconds`, configurable). Ensuite il est refusé.
- Le code est refusé s'il est inconnu, expiré, si le serment est complet (`oath.max-players`, 10 par défaut) ou si le joueur en est déjà partie.
- Protection contre le « deviner un code » : après 5 codes erronés (`oath.code-max-failed-attempts`), le joueur est bloqué 5 minutes (`oath.code-lockout-seconds`).

### Activation et parties

- Le serment devient **actif dès que le fondateur et au moins un autre joueur l'ont accepté**. D'autres joueurs peuvent encore rejoindre tant que le code est valide.
- Les **parties** du serment sont le fondateur et tous les joueurs qui ont accepté. Chaque partie est liée à partir de son acceptation, doit respecter la promesse, et peut **porter plainte contre n'importe quelle autre partie** en cas de manquement.
- Le fondateur est prévenu chaque fois qu'un joueur rejoint.
- Si personne ne rejoint avant l'expiration du code, le serment passe à `PROPOSITION_EXPIRÉE`.
- Permission : `serverrules.oath` (donnée à tous par défaut).
- Statuts d'un serment : `PROPOSÉ` (code émis, personne n'a rejoint), `PROPOSITION_EXPIRÉE`, `ACTIF`, `EXPIRÉ` (durée écoulée), `ANNULÉ` (accord commun), `NON_RESPECTÉ`, `TRAITÉ`.

### Types de sanction d'un serment

1. **Sanctions des règles** : `TEMP_BAN` (durée) ou `FINE` (montant). Elles sont appliquées automatiquement par `BanService` et `FineService` quand le juge les valide.
2. **Sanction personnalisée** (`CUSTOM`) : texte libre, ex : « miner un full diamant pour l'autre ».

**Aucune limite** n'est imposée sur la sanction choisie (montant d'amende, durée de ban ou contenu de la sanction personnalisée). La sanction est la même pour toutes les parties : elle s'applique à celle qui a manqué à son serment.

> Le type « service rendu » reste supprimé du système de **règles**. Il n'existe que pour les serments, sous la forme d'une sanction personnalisée en texte libre.

### Non-respect d'un serment

- Une partie lésée dépose une plainte (`/plaint`) en citant l'identifiant du serment et l'accusé. Le plugin vérifie que le plaignant et l'accusé sont **bien deux parties de ce serment**.
- La plainte est possible pendant la durée du serment et **jusqu'à 2 jours après son expiration** (`oath.complaint-grace-days`, configurable), pour un manquement commis pendant la durée du serment.
- **Aucune plainte n'est possible sur un serment annulé** (statut `ANNULÉ`), même pour un manquement antérieur à l'annulation et même dans le délai de 2 jours. Le bouton est masqué et la commande refusée.
- Le juge traite la plainte comme les autres : il consulte le serment (texte, parties, durée, sanction) et décide d'appliquer la sanction ou de classer sans suite. Le conflit d'intérêts s'applique : un juge partie du serment ne peut pas traiter la plainte (Phase 6).
- Pour un serment, le juge peut **uniquement appliquer la sanction prévue, telle quelle**. Aucune modulation (les limites de la Phase 6 ne concernent que les sanctions des règles), même pour un superjuge.
- Seuls les **superjuges** peuvent appliquer d'autres sanctions en plus de celle prévue, directement avec `/eco take` (EssentialsX) et `/ban`. **Le plugin n'implémente rien pour cela** : ces commandes existent déjà et sont protégées par leurs propres permissions.
- Sanction `TEMP_BAN` ou `FINE` : appliquée par le plugin.
- Sanction `CUSTOM` : le plugin **ne peut pas l'exécuter automatiquement**. La plainte passe au statut `SANCTION_PERSONNALISÉE_À_EXÉCUTER`. Le juge suit son exécution et la marque comme réalisée.
- Tous les serments, leurs parties, leur sanction et leur issue sont enregistrés en SQLite.

### Annulation d'un serment

- Tout serment (en attente de participants ou actif) peut être supprimé si **toutes ses parties actuelles sont d'accord**.
- La « preuve » de l'accord est la confirmation de chaque partie dans le plugin : une partie demande l'annulation, **toutes les autres la confirment**. Le serment passe alors à `ANNULÉ` : plus aucune plainte n'est possible dessus, y compris pour un manquement commis avant l'annulation.
- La demande d'annulation expire après un délai configurable (`oath.cancel-request-expiry-seconds`).

## Phase 10 : Commandes

Principe : chaque action disponible en **GUI** (menu d'inventaire) existe aussi en **commande**. La GUI appelle la même logique que les commandes. Les noms de commandes sont tous configurables (nom + alias) dans `config.yml`. Noms principaux : `/plaint`, `/judge`, `/oath`.

### Règles

| Commande | Qui | Effet |
|---|---|---|
| `/rules` | Tous | Affiche toutes les règles (tableau dans le chat) |
| `/rules <n°>` | Tous | Affiche une règle |
| `/rules new` | Tous | Revoir les nouveautés (ajouts, modifications, suppressions) |
| `/rules gui` | Juges (`serverrules.judge`) | Ouvre la **GUI des règles des juges** (lecture) |
| `/rules manage` | Superjuges (`serverrules.superjudge`) | Ouvre la **GUI des règles des superjuges** (modification) |
| `/rules add` `edit <n°>` `remove <n°>` `move <n°> <position>` `reload` | Une permission par commande | Gérer les règles (confirmation obligatoire pour `add`, `edit`, `remove` ; aucune pour `move` et `reload`) |
| `/rules confirm` | Celui qui a lancé une modification | Confirme la modification en attente |

### Plaintes (joueurs)

| Commande | Effet |
|---|---|
| `/plaint` | Ouvre directement la **GUI** : mes plaintes + bouton « Nouvelle plainte ». Alias : `/plainte`, `/complaint`, `/complaints` |
| `/plaint <joueur> <règle ou serment> <détails>` | Dépose une plainte en une ligne. Le 2e argument est un numéro de règle (`3`) ou un identifiant de serment (`oath:12`) |

### Juge et superjuge

| Commande | Effet |
|---|---|
| `/judge` (alias : `/juge`) | Ouvre directement la **GUI des plaintes** |
| `/judge list [statut]` | Liste les plaintes dans le chat |
| `/judge view <id>` | Détail d'une plainte |
| `/judge sanction <id>` | Applique la sanction prévue (avec confirmation) |
| `/judge sanction <id> <valeur>` | Applique une valeur modulée (règles uniquement, dans les limites du rôle, avec confirmation) |
| `/judge dismiss <id>` | Classe sans suite |
| `/judge done <id>` | Marque une sanction personnalisée comme exécutée |
| `/judge rule <id> <n°>` | Change la règle citée (**superjuge uniquement**) |

Toutes ces actions respectent la règle de conflit d'intérêts (Phase 6).

### Serments

| Commande | Effet |
|---|---|
| `/oath` | Ouvre directement la **GUI des serments** (voir Phase 11) |
| `/oath create` | Ouvre la **GUI de création** d'un serment |
| `/oath create <durée> fine <montant> <texte>` | Création en une ligne, amende |
| `/oath create <durée> ban <durée du ban> <texte>` | Création en une ligne, ban temporaire |
| `/oath create <durée> custom "<sanction>" <texte>` | Création en une ligne, sanction personnalisée |
| `/oath join <code>` | Rejoindre un serment avec son code (affiche le récapitulatif, puis Accepter / Refuser) |
| `/oath info <id>` | Détail d'un serment |
| `/oath cancel <id>` | Demande l'annulation, ou la confirme si une autre partie l'a déjà demandée (aussi via boutons cliquables) |

- À la création (GUI ou ligne de commande), le fondateur reçoit le code dans le chat.

## Phase 11 : Fonctionnement des menus GUI

### Règles générales

- Menus d'inventaire Paper (54 slots pour les listes). Liste paginée : **45 éléments par page**, dernière ligne réservée à la navigation (page précédente, fermer, page suivante).
- **Tous les clics sont annulés** (aucun objet ne peut être pris, déplacé ou glissé, y compris shift-clic et glisser-déposer).
- Chaque bouton vérifie la **permission correspondante** et le **conflit d'intérêts** au clic, pas seulement à l'ouverture. Un bouton non autorisé est masqué.
- Noms et descriptions des objets en MiniMessage, dans les fichiers de langue.
- **Saisie de texte dans le chat** : quand un menu a besoin d'une valeur, il se ferme et un message invite le joueur à écrire dans le chat.
  - Le message saisi n'est **pas diffusé** aux autres joueurs.
  - Le joueur écrit `annuler` (ou clique le bouton cliquable dans le chat) pour revenir au menu sans rien changer.
  - Délai d'attente : 60 secondes (`gui.input-timeout-seconds`). Passé ce délai, la saisie est annulée.
  - Une valeur invalide (montant négatif, durée mal écrite, numéro de règle inexistant) redemande la saisie avec un message d'erreur.
- **Confirmation** (petit menu « Confirmer / Annuler » avec un récapitulatif) dans deux cas :
  1. avant d'**appliquer une sanction** ;
  2. avant **toute modification du contenu des règles** (ajout, modification, suppression). Le **déplacement** d'une règle n'en demande pas.
- Aucune autre action ne demande de confirmation.

### 1. GUI des règles des juges (`/rules gui`)

- Réservée aux juges (`serverrules.judge`), en lecture seule.
- Un objet par règle, dans l'ordre : nom, description et sanction dans la description de l'objet.
- Les règles ajoutées ou modifiées depuis la dernière visite du joueur sont mises en évidence (effet de brillance).
- `/rules` seul affiche toujours le tableau dans le chat, pour tout le monde.

### 2. GUI des plaintes (`/judge`)

- Un objet par plainte (tête du joueur accusé), **de la plus ancienne à la plus récente**.
- Description de l'objet : numéro de plainte, plaignant, règle ou serment cité, date, statut.
- Par défaut, seules les plaintes **en attente** sont affichées. Un bouton permet d'afficher les plaintes déjà traitées.
- Les plaintes dans lesquelles le juge est impliqué n'apparaissent pas (sauf permission `serverrules.conflict.bypass`).
- Clic sur une plainte : ouvre son détail.

### 3. Détail d'une plainte (juge et superjuge)

- Objet d'information : plaignant, accusé, règle ou serment cité (avec ses parties), sanction prévue, détails écrits par le plaignant, date.
- Boutons :
  - **Appliquer la sanction** : ouvre le menu de confirmation, puis applique.
  - **Moduler** (sanctions des règles uniquement) : saisie de la valeur dans le chat. Le message rappelle la fourchette autorisée au rôle (40 % à 200 % pour un juge, 10 % à 500 % pour un superjuge). Ouvre ensuite la confirmation.
  - **Classer sans suite**.
  - **Marquer comme exécutée** (sanction personnalisée d'un serment uniquement).
  - **Changer la règle citée** (superjuge uniquement) : saisie du numéro dans le chat.
  - **Retour**.

### 4. Plaintes du joueur (`/plaint`)

- Écran principal : un objet par plainte déposée par le joueur (accusé, règle ou serment cité, date, statut, issue). Lecture seule, le joueur ne voit que ses propres plaintes.
- Bouton **Nouvelle plainte** :
  1. saisie dans le chat du nom du joueur accusé ;
  2. choix de ce qui est cité : **une règle** (liste des règles) ou **un serment** (liste des serments communs au plaignant et à l'accusé, actifs ou expirés depuis moins de 2 jours, jamais annulés) ;
  3. saisie des détails dans le chat ;
  4. récapitulatif avec **Envoyer** et **Annuler**.

### 5. Création d'un serment (bouton « Créer un serment » ou `/oath create`)

1. Saisie de la **durée** dans le chat (sauf si donnée dans la commande).
2. Menu de choix du type de sanction : **amende**, **ban temporaire** ou **personnalisée**.
3. Saisie dans le chat de la valeur : montant, durée du ban, ou texte de la sanction personnalisée.
4. Saisie dans le chat du texte de la promesse.
5. Menu de récapitulatif : durée, sanction, promesse. Boutons **Créer** et **Annuler**.
6. Le serment est créé et le **code** est donné au fondateur dans le chat (bouton cliquable pour le copier).

### 6. GUI des serments (`/oath`)

- **Liste principale** : les serments **actifs auxquels le joueur participe**, ainsi que ses serments en attente de participants (dont il est le fondateur). Un objet par serment, dont la couleur dépend du statut.
- **Trois boutons** sur la ligne du bas, à côté de la navigation :
  - **Rejoindre un serment** : saisie du code dans le chat, puis menu de récapitulatif du serment avec **Accepter** et **Refuser**.
  - **Créer un serment** : lance le parcours de création (section 5).
  - **Serments expirés** : ouvre la liste de **tous** les serments terminés auxquels le joueur a participé (durée écoulée), **quelle que soit leur ancienneté**, y compris au-delà des 2 jours. Les serments annulés y apparaissent aussi, marqués « annulé ». Lecture seule.
- **Détail d'un serment** (clic sur un objet) : parties, promesse, durée, sanction, statut. Le fondateur y voit le **code** tant qu'il est valide.
- Boutons du détail selon la situation :
  - **Demander l'annulation** / **Confirmer l'annulation** (serment en attente ou actif)
  - **Porter plainte** : serment actif, ou expiré depuis moins de 2 jours, **et jamais annulé**. Choix de l'accusé parmi les autres parties, saisie des détails dans le chat, plainte créée avec le serment cité. Pour un serment expiré depuis plus de 2 jours ou annulé, le bouton est masqué.

### 7. GUI des règles des superjuges (`/rules manage`)

- Réservée aux superjuges (`serverrules.superjudge`). Les actions vérifient aussi la permission de la commande équivalente (`add`, `edit`, `remove`, `move`).
- **Une règle par ligne**, 5 règles par page, avec **Ajouter une règle** en bas du menu.
- Sur chaque ligne : l'objet de la règle (clic = modifier), un bouton **monter**, un bouton **descendre** et un bouton **supprimer**.
- Menu de modification d'une règle : boutons **Nom**, **Description**, **Type de sanction** (bascule entre amende et ban temporaire) et **Valeur** (montant ou durée). Les saisies se font dans le chat.
- **Ajout, modification et suppression** passent par le menu de confirmation (récapitulatif de ce qui change) avant d'être appliqués, puis déclenchent les notifications décrites en Phase 3.
- **Monter / descendre** une règle s'applique immédiatement, sans confirmation et sans notification.

---

## Phase 12 : Qualité

- Code découpé en classes : commandes, listeners, stockage, config, plaintes, sanctions (Vault et bans).
- Gestion des erreurs et des valeurs nulles.
- Tests sur un serveur **Paper 26.3** local (Java 25) avec Vault et EssentialsX (config `min-money` négative). Vérifier d'abord que EssentialsX et Vault démarrent correctement sur 26.3.
- README rédigé en anglais : installation, dépendances, permissions, configuration, ajout d'une langue.

## Phase 13 : Autres versions (optionnel, après la phase 12)

La cible prioritaire reste **Paper 26.3**. Cette phase ne commence que quand tout le reste est validé.

- Objectif : supporter d'autres versions de Paper, par exemple 26.1.x, 26.2 et 1.21.11, si c'est possible sans casser 26.3.
- Isoler tout ce qui dépend de la version de Paper (API de commandes, menus, ban) derrière des interfaces, pour pouvoir proposer plusieurs implémentations.
- Les versions 26.x utilisent Java 25, alors que 1.21.x utilise Java 21 : prévoir des builds ou modules séparés si nécessaire.
- Chaque version supportée doit être testée sur un serveur réel, avec une version d'EssentialsX officiellement compatible.
- Si une version demande de dégrader une fonctionnalité de 26.3, elle n'est pas supportée.

---

## Récapitulatif des permissions

| Permission | Rôle |
|---|---|
| `serverrules.complaint` | Déposer une plainte (tous par défaut) |
| `serverrules.oath` | Proposer et accepter des serments (tous par défaut) |
| `serverrules.judge` | Juge : recevoir et traiter les plaintes, GUI `/judge` et `/rules gui` |
| `serverrules.superjudge` | Superjuge : tout ce que fait le juge + changer la règle citée + modulation 10 % à 500 % + GUI `/rules manage` |
| `serverrules.conflict.bypass` | Permet de traiter les cas dans lesquels on est impliqué (à attribuer avec LuckPerms) |
| `serverrules.add` / `.edit` / `.remove` / `.move` / `.reload` | Administration des règles |

## Points à trancher plus tard

Aucun point bloquant pour le moment.

## Consignes pour Trae

1. Travailler **phase par phase**.
2. Montrer le code de chaque phase.
3. Attendre ma validation avant de passer à la suivante.
4. Cibler **Paper 26.3 avec Java 25** et utiliser uniquement l'API Paper récente (pas de code déprécié). Vérifier dans la documentation Paper les points listés dans « Stack technique ».
