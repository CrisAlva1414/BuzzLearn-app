# Contribuir a BuzzLearn

Gracias por querer aportar. Este documento es corto a propósito.

## Licencia de las contribuciones

BuzzLearn se distribuye bajo **Apache License 2.0**. Al enviar un pull request
declaras que:

1. Tienes derecho a aportar ese código, y
2. Aceptas que tu contribución se licencie bajo Apache 2.0 (inbound = outbound).

No pedimos CLA. No cedemos tu copyright. Tu trabajo sigue siendo tuyo, solo que
bajo la misma licencia permisiva que el resto del proyecto.

## Antes de abrir un PR

- Abre un issue primero si el cambio es grande o toca arquitectura. Nos ahorra
  a todos un PR de 800 líneas que no va a ninguna parte.
- Un PR, un tema. Nada de "arreglo el bug y de paso reformateo el repo".
- Los cambios de comportamiento necesitan tests. La suite corre con
  `./gradlew test`.

## Convenciones de código

- Kotlin oficial + `ktlint`. Tu PR tiene que pasar `./gradlew ktlintCheck`.
- Nada de literales de color en Kotlin: usa los tokens de
  `app/src/main/res/values/colors.xml`. El rebranding white label depende de eso.
- Todo DTO nuevo necesita su `toString()` redactado. Nunca imprimimos correo,
  contraseña, tokens ni códigos de verificación en logs.
- Nada de SDKs de telemetría o crash reporting. `scripts/check-no-telemetry.sh`
  falla el build si aparece uno, y eso es intencional.

## Commits

Formato Conventional Commits:

```
feat(lesson): repaso espaciado por caja de Leitner
fix(session): evitar refresh duplicado en cold start
docs(readme): explicar el modelo white label
```

## Reportar bugs y vulnerabilidades

- Bugs y features: issues del repositorio, con pasos para reproducir.
- Vulnerabilidades de seguridad: **no abras un issue público**. Escribe a los
  mantenedores directamente y damos plazo razonable antes de publicar detalles.
