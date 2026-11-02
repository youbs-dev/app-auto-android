package fr.brice.scenictrajets

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Traduit une erreur technique en phrase compréhensible (le détail reste dans le journal). */
object UserMessages {
    fun of(e: Throwable): String = when (e) {
        is SecurityException -> "Autorisation manquante : ouvre Scénic Trajets sur le téléphone"
        is UnknownHostException -> "Pas de connexion Internet"
        is SocketTimeoutException -> "Le serveur ne répond pas, réessaie plus tard"
        is IOException -> "Problème de connexion, réessaie plus tard"
        else -> "Une erreur est survenue, réessaie dans un instant"
    }
}
