package de.nbeck.aapshc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Von Health Connect verlangte Erklärung, wofür die Daten genutzt werden. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Datenschutz", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Die App liest Trainings, Schlaf, Schritte, Herzfrequenz, Distanz und Kalorien " +
                                "aus Health Connect und schreibt daraus Einträge in deine eigene Nightscout-Instanz, " +
                                "damit AAPS sie anzeigen kann."
                        )
                        Text(
                            "Die Daten gehen ausschließlich an die von dir eingetragene Nightscout-URL. " +
                                "Es gibt keine Werbung, keine Analyse und keine weiteren Server."
                        )
                    }
                }
            }
        }
    }
}
