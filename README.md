# Scénic Trajets

App Android Auto (sur le téléphone) qui enregistre les trajets du Scénic E-Tech : parcours GPS, batterie et autonomie lues dans la voiture, consommation, coût, recharges, et envoi vers Dawarich.

## Ce que fait l'app

- **Dans la voiture** : trajet en cours en direct, batterie, total du jour, bouton Démarrer / Arrêter, derniers trajets, bornes de recharge proches, écran Infos.
- **Sur le téléphone** : historique par jour, détail et carte de chaque trajet, bilan du mois, journal des recharges, réglages (prix du kWh, véhicules, Dawarich, conservation).
- **Trajets automatiques** : l'app détecte la conduite et démarre seule ; le trajet se termine quand Android Auto se déconnecte ou après 10 min à l'arrêt.
- **Export** : CSV (tableur) et GPX (Dawarich), envoi direct à Dawarich dès qu'Internet est disponible.

## Compilation et publication

- Chaque envoi sur GitHub lance la compilation (GitHub Actions) : APK de debug et bundle Google Play signé.
- Un message de commit contenant `[publier]` (ou une étiquette `publier-…`) publie le bundle sur le canal **Tests internes** de Google Play. Augmenter `versionCode` avant.
- Android Auto n'accepte les apps à templates que si elles viennent du Play Store : on teste dans la voiture via Tests internes.

## Versions récentes

- **0.27** : interface finalisée : nom sans « (test) », nouvelle icône et écran de démarrage, icônes Material à la place des emojis (téléphone et voiture), états vides soignés, « À propos » dans les Réglages, Diagnostic renommé Infos, messages d'erreur lisibles.
- **0.26** : fin d'un trajet terminé à l'arrêt corrigée (plus 10 min en trop), distance sans la dérive du GPS à l'arrêt, compteur de la voiture en contrôle, GPS plus économe (lent en attente et à l'arrêt, coupé 2 min après la déconnexion d'Android Auto), notification avec km, durée, batterie et bouton Arrêter.
- **0.25** : correction de la batterie par véhicule, accueil du téléphone allégé.
