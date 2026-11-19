<div align="center">

# Pulse4

**Centro personal de fitness, biometría y actividades para Android, privado y sin servidores**

[![English](https://img.shields.io/badge/English-232A3B?style=for-the-badge)](README.md)
[![Čeština](https://img.shields.io/badge/Čeština-232A3B?style=for-the-badge)](README.cs.md)
[![Deutsch](https://img.shields.io/badge/Deutsch-232A3B?style=for-the-badge)](README.de.md)
[![Español](https://img.shields.io/badge/Español-0284C7?style=for-the-badge)](README.es.md)
[![Français](https://img.shields.io/badge/Français-232A3B?style=for-the-badge)](README.fr.md)
[![简体中文](https://img.shields.io/badge/简体中文-232A3B?style=for-the-badge)](README.zh.md)

---

[![Android](https://img.shields.io/badge/Plataforma-Android%2014%2B-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Pruebas-349%2B%20Superadas-10B981.svg?style=flat)](app/src/test)
[![Licencia](https://img.shields.io/badge/Licencia-Apache%202.0-blue.svg?style=flat)](LICENSE)

</div>

Pulse4 es una aplicación privada de Android diseñada para centralizar las métricas corporales, entrenamientos y recuperación fisiológica de los ecosistemas **Garmin** y **Withings**. Se rige por el principio de **Zero Cloud Dependency**: no requiere servidores centrales, suscripciones ni cuentas de terceros. Todos los datos sensibles, bases de datos cifradas (SQLCipher) y credenciales residen exclusivamente en los dispositivos de los usuarios.

El intercambio selectivo de métricas con familiares o entrenadores se realiza mediante la red en malla criptográfica punto a punto **Reticulum Mesh / LXMF**.

---

## Características Principales

### 1. Ecosistema Withings (Básculas y Salud)
- **Conexión web directa sin cuenta corporativa de desarrollador:** Sincronización completa sin tarifas de API ni servidores externos de retorno (callback).
- **Composición corporal integral:** Peso, masa muscular, porcentaje de grasa, agua corporal total (hidratación), masa ósea y grasa visceral.
- **Análisis segmental del cuerpo:** Desglose detallado de músculo y grasa en extremidades y tronco (brazo derecho/izquierdo, torso, pierna derecha/izquierda), proporciones y cálculo de simetría bilateral (% de diferencia).
- **Electrocardiograma (ECG):** Trazados de básculas Withings Body Scan y relojes ScanWatch, clasificación del ritmo (sinusal frente a fibrilación auricular AFib), frecuencia cardíaca e intervalo QTc en milisegundos.
- **Mantenimiento silencioso de sesión (keepalive):** Extensión automática de sesión en segundo plano y renovación rápida en un clic.

### 2. Ecosistema Garmin (Actividades y Recuperación)
- **Biometría completa:** Variabilidad de frecuencia cardíaca nocturna (HRV / rMSSD), pulso en reposo durante el sueño, Body Battery (0–100), nivel de estrés, SpO2 y frecuencia respiratoria.
- **Dinámica de carrera y telemetría:** Resistencia en tiempo real y potencial (Stamina), dinámica de carrera completa (cadencia, oscilación vertical, relación vertical, tiempo de contacto con el suelo GCT y equilibrio izq/der), zonas de frecuencia cardíaca y condición de rendimiento.
- **Planificador de entrenamientos:** Creación de entrenamientos estructurados por intervalos con exportación directa a relojes Garmin.

### 3. Interfaz Moderna y Alta Resolución (Jetpack Compose M3)
- **Alta granularidad en gráficos (±100 g a 500 g):** Escala dinámica independiente en `MultiSeriesLineChart` con un margen mínimo de 0,80 kg: variaciones de 200 g ocupan el 25 % de la altura del gráfico sin perderse en líneas planas.
- **Lectura de incrementos en gramos:** Diferencias diarias expresadas directamente en gramos (`+200 g`, `-500 g`, `±0 g`) y HUD táctil interactivo.
- **Gráfico de área apilada de composición corporal:** Representación continua de masa muscular, grasa y peso total con opción de desplegar tarjetas individuales.
- **Filtros desplegables compactos:** Selección múltiple mediante casillas, conjuntos predefinidos (Recuperación, Corazón y ECG, Composición corporal) y selección de período (7 a 365 días).
- **Diseño sin textos innecesarios bajo los gráficos:** Explicaciones fisiológicas accesibles mediante el botón informativo `ⓘ`.
- **Fondos temáticos ambientales:** Motivos fotográficos para las tarjetas de resumen y actividades (carrera, caminata, senderismo, natación, fuerza).

### 4. Compartición Descentralizada (Reticulum Mesh)
- **Sin nube central:** Comunicación directa P2P entre dispositivos.
- **Cifrado de extremo a extremo (E2EE v3):** Claves de identidad Curve25519/Ed25519 con clave efímera X25519 del remitente por mensaje (ephemeral-static ECIES, protegiendo mensajes pasados si se compromete la clave del remitente) y sobres internos con metadatos minimizados.
- **Permisos granulares:** Control total sobre qué datos se comparten con cada contacto.

---

## Estructura del Proyecto

```
pulse4/
├── domain/                      # Lógica de dominio en Kotlin puro: entidades, deduplicación
├── analytics/                   # Algoritmos de carrera, stamina, perfiles de elevación
├── connector-garmin-personal/   # Motor de sincronización Garmin Connect y descarga FIT
├── connector-withings/          # Motor de sincronización Withings Web, composición y ECG
├── sync-protocol/               # Capa de transporte cifrado Reticulum / LXMF, cola outbox
├── sharing-policy/              # Políticas de privacidad y proyecciones de datos
├── ui-logic/                    # Lógica de presentación desacoplada del framework
├── app/                         # Aplicación Android (Compose M3, Room SQLCipher, Keystore, native RNS)
└── pulse4-vault/                # Documentación del proyecto y base de conocimiento Obsidian
```

---

## Compilación

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Ejecutar las pruebas unitarias
./gradlew testDebugUnitTest

# Compilar APK de depuración
./gradlew assembleDebug

# Instalar en el dispositivo conectado
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Licencia

Distribuido bajo la [Licencia Apache 2.0](LICENSE).
