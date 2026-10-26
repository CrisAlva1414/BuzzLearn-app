<div align="center">

# BuzzLearn

### Tu propio Duolingo, sobre el tema que tú quieras.

Aprender en dosis diarias, con racha, niveles y repaso espaciado — pero aplicado a **lo que tú enseñas**:
repostería de altura, soldadura de madera, seguridad en faena, lo que sea.

**Open source · White label · Android nativo**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![minSdk](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Licencia](https://img.shields.io/badge/Licencia-open%20source-455A64)](#licencia)
[![Sin telemetría](https://img.shields.io/badge/telemetría-ninguna-455A64)](scripts/check-no-telemetry.sh)

</div>

---

## La idea en 30 segundos

Duolingo funciona no por su contenido, sino por su **mecánica**: lecciones de 3 minutos, rachas, repaso
espaciado, progreso visible. BuzzLearn separa esa mecánica del contenido.

Tú pones el tema, tu marca y tus colores. BuzzLearn pone el motor de aprendizaje.

> **Duolingo te enseña idiomas. BuzzLearn te deja enseñar cualquier cosa.**

## Dónde entra el white label

BuzzLearn es el **esquema**, no el producto final. Cualquier instructor, academia o gremio puede montar
su propio "chiringuito" educativo sin escribir una línea de código:

- **Marca y colores** — la paleta es 100% tokenizada en `res/values/colors.xml`. Rebrands cambiando
  valores de recursos, cero literales de color en Kotlin.
- **Contenido** — cursos, lecciones, preguntas y ejercicios llegan desde el backend, no del APK.
- **Distribución** — cada instancia se compila con su `applicationId`, su nombre y su ícono.

El resultado es una app propia en la tienda, con la cara de tu marca, corriendo el mismo motor:

```bash
BUZZLEARN_BASE_URL=https://api.tu-academia.cl/ ./gradlew bundleRelease
```

## Para quién es

- **Instructores e independientes** que ya tienen alumnos y contenido, pero cero app.
- **Academias y OTECs** que quieren ofrecer práctica diaria y no solo clases presenciales.
- **Equipos de capacitación** que necesitan microlearning medible en terreno.
- **Cualquiera** que sepa algo que merezca practicarse todos los días.

## Cómo funciona por dentro

Este repositorio es el **cliente Android**. El backend vive en un repositorio privado separado; el
contrato entre ambos es una especificación OpenAPI versionada.

```
┌──────────────────────┐   HTTPS / REST   ┌──────────────────────┐
│   BuzzLearn App      │  ──────────────▶ │   BuzzLearn API      │
│   (este repo)        │   JSON tipado    │   (repo privado)     │
│   Kotlin · Compose   │ ◀──────────────  │   OpenAPI v0         │
└──────────────────────┘                  └──────────────────────┘
```

El cliente es una app nativa honesta: sin WebView, sin framework híbrido, sin atajos.

## Stack

- **Kotlin 2.4** · JDK 21 · `minSdk` 26 (Android 8.0+)
- **Jetpack Compose** con **Material 3** y **Navigation 3**
- **Retrofit 3** · **OkHttp 5** · **kotlinx.serialization**
- **Coroutines** · **DataStore** para el token de refresco
- **Coil 3** para imágenes · **WorkManager** en el roadmap
- **ktlint** en build · tests de unidad con JUnit 4 y **MockWebServer**

## Qué está implementado hoy

El **núcleo de autenticación y sesión está completo y testeado** — que es lo que hace o rompe un
producto de aprendizaje recurrente:

- **API de auth tipada** — registro, verificación de email (con reenvío), login, refresh, logout y
  recuperación de contraseña por código.
- **Access token solo en memoria** — nunca en disco, nunca en logs.
- **Refresh single-flight con rotación** — N llamadas concurrentes que fallan por token vencido
  generan **un** refresh, no N. Cubierto por `SessionConcurrencyTest`.
- **Cierre de sesión durable** — revocación en servidor, limpieza local y reintento si la red falla.
- **Zona horaria del dispositivo** en cada request — las rachas se calculan bien en cualquier lugar.
- **Redacción en logs** — `toString()` de cada DTO enmascara correo, contraseña, tokens y códigos.
- **Cero telemetría** — sin Firebase, Crashlytics, Sentry ni similares. `scripts/check-no-telemetry.sh`
  lo verifica sobre los archivos de build, el código fuente, los AARs y el classpath de release. El
  build **falla** si aparece uno.
- **Hardening** — backups desactivados, `network_security_config` con HTTPS obligatorio y logging
  de red limitado a builds `debug`.

La UI es todavía un scaffold mínimo: las pantallas de aprendizaje y el motor de curso son el
siguiente hito.

## Empezar

Requisitos: JDK 21, Android SDK (`ANDROID_HOME` configurado) y un backend BuzzLearn accesible.

```bash
git clone https://github.com/CrisAlva1414/BuzzLearn-app.git
cd BuzzLearn-app

./gradlew test             # suite de tests
./gradlew assembleDebug    # APK de desarrollo
./gradlew ktlintCheck      # estilo
./scripts/check-no-telemetry.sh --strict
```

Build de release:

```bash
BUZZLEARN_BASE_URL=https://api.tu-academia.cl/ ./gradlew bundleRelease
```

En `debug` la app apunta a un entorno local; en `release` **exige** una base URL HTTPS.

## Roadmap

- [x] Núcleo de autenticación y sesión (registro, verificación, login, refresh, logout, reset)
- [x] Design system tokenizado para rebranding instantáneo
- [x] Guardia anti-telemetría en CI
- [ ] Motor de curso: lecciones, ejercicios, corrección y repaso espaciado
- [ ] Rachas, metas diarias y notificaciones locales
- [ ] Perfil, progreso y estadísticas
- [ ] SDK público y guía de despliegue white label
- [ ] Licencia definitiva y guía de contribución

## Estado

Alpha temprana. El core de sesión es sólido y está cubierto por tests; las funcionalidades de
aprendizaje están en desarrollo. El proyecto apunta a **open source**; el código de infraestructura y
contrato de API se mantiene privado por ahora.

## Equipo

Construido por [Cristian](https://github.com/CrisAlva1414) y [Rzarate1](https://github.com/Rzarate1).

## Licencia

Open source. El texto de licencia se publicará junto con el primer release estable; mientras tanto,
todos los derechos reservados por sus autores.
