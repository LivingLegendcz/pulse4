package com.google.mlkit.vision.codescanner
class Barcode { val rawValue: String? = null }
class Task<T> { fun addOnSuccessListener(f: (T) -> Unit): Task<T> = this; fun addOnFailureListener(f: (Exception) -> Unit): Task<T> = this }
class Scanner { fun startScan(): Task<Barcode> = TODO() }
object GmsBarcodeScanning { fun getClient(c: android.content.Context): Scanner = TODO() }
