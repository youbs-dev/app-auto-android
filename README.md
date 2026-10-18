# Scénic Trajets : étape 1 (app de test)

Cette petite app Android Auto affiche en direct ce que le Scénic transmet : le modèle, la batterie, l'autonomie, la vitesse et le kilométrage. Chaque ligne affiche soit la valeur, soit la raison de son absence (« non transmis par la voiture », « indisponible pour l'instant », « permission refusée »).

## Installation (une seule fois)

1. Installe [Android Studio](https://developer.android.com/studio) sur ton ordinateur, puis ouvre ce dossier `scenic-trajets` (*File > Open*). Laisse Android Studio télécharger ce qui manque.
2. Sur le téléphone, active le **mode développeur Android** : *Paramètres > À propos > Numéro de build*, à toucher 7 fois. Active ensuite **Débogage USB**.
3. Active le **mode développeur d'Android Auto** : *Paramètres > Appareils connectés > Android Auto*, puis touche « Version » 10 fois. Dans le menu ⋮, ouvre *Paramètres pour les développeurs* et coche **Sources inconnues**.
4. Branche le téléphone en USB et clique sur ▶ *Run* dans Android Studio. L'app n'a pas d'écran sur le téléphone, c'est normal.

## Test dans la voiture

1. Connecte le téléphone au Scénic et lance Android Auto.
2. Ouvre le lanceur d'apps et choisis **Scénic Trajets (test)**.
3. Si la ligne « Permissions » indique des permissions manquantes, touche-la pour les accorder.
4. Roule quelques minutes, puis prends l'écran en photo à l'arrêt.

## Ce que le résultat nous dira

| Ce que tu vois | Ce que ça veut dire pour la suite |
|---|---|
| Batterie et kilométrage affichent des valeurs | Tout peut venir directement de la voiture |
| « non transmis par la voiture » | Il faudra passer par l'API My Renault ou un dongle OBD2 |
| Vitesse seule | Le GPS du téléphone suffit pour la distance, la batterie viendra d'ailleurs |

Les valeurs sont aussi écrites dans le journal Android (Logcat dans Android Studio, filtre `ScenicTest`).
