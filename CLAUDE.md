# Nuvio-Clipper

Kotlin Multiplatform con Compose. Casi todo el código vive en `composeApp`,
repartido en source sets por target: `commonMain`, `androidFull`,
`androidPlaystore`, `desktopMain`, `iosFull`, `iosAppStore`.

## Buscar código: usá el grafo antes que grep

Este repo está indexado con CodeGraph (~1.000 archivos Kotlin, 30k símbolos).
Antes de usar `Grep`, `Glob` o leer archivos a ciegas:

1. Usá la herramienta MCP **`codegraph_explore`** con la pregunta en lenguaje
   natural: "cómo funciona el player", "cómo llega X a Y", "qué se rompe si
   cambio Z". Devuelve el código fuente relevante, los call paths entre
   símbolos y el blast radius en una sola llamada.
2. Sólo si el grafo no alcanza, caé a `Grep` / `Read`.

Grep textual acá es caro y engañoso: son ~193k líneas de Kotlin, y los mismos
nombres de clase y función se repiten en seis source sets. Una búsqueda por
texto devuelve todas las variantes de plataforma sin indicar cuál aplica al
target en cuestión.

## expect / actual

Al tocar una declaración `expect`, buscá **todas** sus implementaciones
`actual` antes de asumir que hay una sola. El caso de referencia es
`AppFeaturePolicy` en `composeApp/src/*/kotlin/com/nuvio/app/core/build/`:
una declaración en `commonMain` y seis implementaciones por target. Un cambio
de firma que compile en desktop puede romper iOS o Android sin que lo veas.

## Qué NO está en el índice

Si la respuesta cae en alguna de estas rutas, el grafo no la ve — leé los
archivos directamente y no asumas que "no existe" porque no aparece:

- **`MPVKit/`** — submódulo git de 2.7 GB (build kit de mpv/ffmpeg, ~6.700
  archivos). Excluido a propósito.
- **`vendor/`** — forks vendorizados: `compose-media-player` (105 archivos
  Kotlin, 3 Swift), `TorrServer`, `quickjs-kt`. Ver nota abajo.
- **`build/`, `.gradle/`** — salidas de compilación. Excepción: los archivos
  bajo `com/nuvio/app/core/build/` SÍ están indexados; son fuente real.

> Estado de `vendor/`: al momento de escribir esto quedó fuera del índice
> porque CodeGraph saltea `vendor` como directorio de dependencias. El
> `codegraph.json` ya tiene `"include": ["vendor/"]` para revertirlo. Verificar
> con `codegraph status`: si Kotlin marca 1.113 y Swift 12, `vendor/` entró y
> esta nota se puede borrar. Si marca 1.008 y 9, sigue afuera.

## Mantenimiento del índice

CodeGraph se auto-sincroniza con un file watcher. Si el grafo devuelve
resultados que no coinciden con el código en disco, corré `codegraph sync`
(o `codegraph index --force` para reconstruir de cero).
