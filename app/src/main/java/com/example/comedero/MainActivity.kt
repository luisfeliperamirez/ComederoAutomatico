package com.example.comedero

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.thread

// ---------- BASE DE DATOS SQLite ----------
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "comedero.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usuarios(usuario TEXT PRIMARY KEY, salt TEXT, hash TEXT)")
        db.execSQL("CREATE TABLE alimentaciones(id INTEGER PRIMARY KEY AUTOINCREMENT, fecha TEXT, tipo TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    // Contraseña cifrada
    private fun hash(clave: String, salt: String): String {
        val spec = PBEKeySpec(clave.toCharArray(), salt.toByteArray(), 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    fun registrar(u: String, c: String): Boolean {
        val salt = UUID.randomUUID().toString()
        val cv = ContentValues().apply {
            put("usuario", u); put("salt", salt); put("hash", hash(c, salt))
        }
        return writableDatabase.insert("usuarios", null, cv) != -1L
    }

    fun login(u: String, c: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT salt, hash FROM usuarios WHERE usuario = ?", arrayOf(u)
        ).use { return it.moveToFirst() && hash(c, it.getString(0)) == it.getString(1) }
    }

    fun guardar(tipo: String) {
        val f = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val cv = ContentValues().apply { put("fecha", f); put("tipo", tipo) }
        writableDatabase.insert("alimentaciones", null, cv)
    }

    fun historial(): List<String> {
        readableDatabase.rawQuery(
            "SELECT fecha, tipo FROM alimentaciones ORDER BY id DESC LIMIT 50", null
        ).use { c ->
            val lista = mutableListOf<String>()
            while (c.moveToNext()) lista.add("${c.getString(0)} - ${c.getString(1)}")
            return lista
        }
    }
}

// ---------- ACTIVITY ----------
class MainActivity : ComponentActivity() {
    private lateinit var db: Db
    private var socket: BluetoothSocket? = null

    private val estado = mutableStateOf("Desconectado")
    private val dispositivos = mutableStateOf<List<BluetoothDevice>>(emptyList())
    private val historial = mutableStateOf<List<String>>(emptyList())

    private val permiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cargarDispositivos()
    }

    @SuppressLint("MissingPermission")
    private fun cargarDispositivos() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            dispositivos.value = adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            estado.value = "Falta permiso de Bluetooth"
        }
    }

    // Conexión Bluetooth
    @SuppressLint("MissingPermission")
    private fun conectar(dev: BluetoothDevice) = thread {
        try {
            estado.value = "Conectando..."
            val s = dev.createRfcommSocketToServiceRecord(
                UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            )
            s.connect()
            socket = s
            estado.value = "Conectado a ${dev.name}"
            val lector = s.inputStream.bufferedReader()
            while (true) {
                val linea = lector.readLine() ?: break
                if (linea.startsWith("EVT:FED")) {
                    db.guardar(if (linea.endsWith("M")) "MANUAL" else "TEMPORIZADOR")
                    historial.value = db.historial()
                }
            }
        } catch (e: Exception) {
        }
        estado.value = "Desconectado"
    }

    private fun enviar(cmd: String) = thread {
        try {
            socket?.outputStream?.write("$cmd\n".toByteArray())
        } catch (e: Exception) {
            estado.value = "Error al enviar"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        if (Build.VERSION.SDK_INT >= 31) permiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else cargarDispositivos()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding()) { App() }
                }
            }
        }
    }

    // ---------- PANTALLAS ----------
    @Composable
    fun App() {
        var logueado by remember { mutableStateOf(false) }
        if (logueado) Principal { logueado = false } else Login { logueado = true }
    }

    @Composable
    fun Login(onOk: () -> Unit) {
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Comedero Automático", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Usuario") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Contraseña") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            Text(msg, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (db.login(user.trim(), pass)) onOk() else msg = "Usuario o contraseña incorrectos"
                }) { Text("Entrar") }
                OutlinedButton(onClick = {
                    msg = if (user.trim().length < 3 || pass.length < 6) "Usuario mín. 3 y clave mín. 6 caracteres"
                    else if (db.registrar(user.trim(), pass)) "Cuenta creada, ya puedes entrar"
                    else "Ese usuario ya existe"
                }) { Text("Registrarse") }
            }
        }
    }

    @Composable
    fun Principal(onSalir: () -> Unit) {
        var horas by remember { mutableStateOf("0") }
        var minutos by remember { mutableStateOf("1") }
        val estadoActual by estado
        val devs by dispositivos
        val hist by historial
        LaunchedEffect(Unit) { historial.value = db.historial() }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estado: $estadoActual", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onSalir) { Text("Salir") }
            }

            Text("Dispositivos vinculados:")
            @SuppressLint("MissingPermission")
            devs.forEach { d ->
                OutlinedButton(onClick = { conectar(d) }, modifier = Modifier.fillMaxWidth()) {
                    Text(try { d.name ?: d.address } catch (e: SecurityException) { d.address })
                }
            }

            Button(onClick = { enviar("F") }, modifier = Modifier.fillMaxWidth()) {
                Text("Alimentar ahora")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(horas, { horas = it.filter(Char::isDigit).take(2) },
                    label = { Text("Horas") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(minutos, { minutos = it.filter(Char::isDigit).take(2) },
                    label = { Text("Min") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val h = horas.toIntOrNull() ?: 0
                    val m = minutos.toIntOrNull() ?: 0
                    if (h in 0..23 && m in 0..59 && (h > 0 || m > 0)) enviar("T,$h,$m")
                }, modifier = Modifier.weight(1f)) { Text("Iniciar timer") }
                OutlinedButton(onClick = { enviar("C") }, modifier = Modifier.weight(1f)) {
                    Text("Cancelar")
                }
            }

            TextButton(onClick = { db.guardar("SIMULADO"); historial.value = db.historial() }) {
                Text("Simular alimentación (prueba)")
            }

            Text("Historial", style = MaterialTheme.typography.titleMedium)
            hist.forEach { Text(it) }
        }
    }
}