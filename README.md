# Factura Clara · Android

Aplicación para tablet Android que convierte fotos y PDF de facturas o boletas en un **nuevo PDF limpio, con texto digital seleccionable**. Trabaja dentro de la tablet, sin cuentas, suscripciones, servidores ni claves de API. No incluye editor ni correcciones manuales.

**Es una primera versión. No garantiza transcripción perfecta y todavía no está validada con 100 facturas reales diferentes.** Las comprobaciones pueden detectar algunos problemas, pero también pueden dejar pasar errores. La coincidencia de varias lecturas del mismo motor no demuestra exactitud.

## Uso

1. Copia `output/android/Factura-Clara-0.2.7.apk` a tu tablet con Android 6.0 o posterior y ábrelo para instalarlo. Si Android lo solicita, autoriza la instalación desde la aplicación con la que abriste el archivo. Comprueba antes su SHA-256 en `output/android/SHA256SUMS.txt`. Es la última versión publicada y es un APK de prueba: variante debug, depurable y firmada con la clave de depuración de la máquina de desarrollo, no una publicación en Play Store. La versión 0.2.8 todavía no tiene APK publicado; ver [Publicar una versión](#publicar-una-versión).
2. Toca **＋ Agregar boleta** y elige una foto o un PDF ya guardado en la tablet. Puedes seleccionar varios archivos; cada uno se procesa como un documento independiente.
3. La aplicación lee cada página, realiza comprobaciones y genera automáticamente el PDF siempre que la página tenga texto reconocible.
4. Con estado **Listo** (**PDF listo** en el detalle), las lecturas se confirmaron sin bloqueos. Toca **Guardar PDF** para elegir dónde guardarlo. El texto mostrado en pantalla es de solo lectura.
5. Con estado **Revisar** (**Revisa este PDF antes de usarlo** en el detalle), las lecturas no se pudieron confirmar del todo. El PDF contiene la lectura principal sin correcciones y **resalta en amarillo** los fragmentos que la segunda lectura no respalda o que tienen confianza baja; la cabecera y el pie de esas páginas lo indican. Compáralos con la foto original antes de usar el documento. El resaltado es orientativo: un fragmento sin resaltar también puede contener errores. Los avisos de la lectura, con su texto completo, están en **Ver detalles**.
6. Si aparece **Necesita otra foto**, la página no tiene texto reconocible. Toca **Subir otra foto** y elige un archivo más legible. La aplicación no inventa texto ni cifras.

La aplicación recibe fotos ya tomadas; no abre la cámara. No necesita permiso de almacenamiento general: el selector del sistema da acceso a los archivos locales que elijas. Importa una copia idéntica del archivo original, incluidos sus metadatos; el OCR adapta la resolución cuando lo exige el límite de memoria. Puedes elegir una carpeta local al guardar. La aplicación compilada no necesita descargar modelos de OCR durante la instalación ni durante el uso.

Si ya instalaste una versión anterior firmada con la misma clave, abre el nuevo APK y elige actualizar. No hace falta desinstalar la aplicación para actualizarla; así conservas los documentos que ya importaste.

Las versiones hasta 0.2.7 están firmadas con la clave de depuración. El primer APK firmado con una clave de release no puede instalarse encima: Android lo rechaza por firma distinta y obliga a desinstalar, lo que borra los documentos importados. Antes de ese cambio, guarda fuera de la aplicación los PDF que quieras conservar. A partir de ahí, las actualizaciones firmadas con la misma clave de release vuelven a instalarse sin desinstalar.

## Alcance de esta versión

- Modelos incluidos en el APK: ML Kit latino, Tesseract inglés/español y PaddleOCR `latin_PP-OCRv5_mobile_rec`, ejecutado localmente con ONNX Runtime. ML Kit mantiene la ruta inicial; Tesseract y Paddle se utilizan en comprobaciones adicionales cuando procede. Desde 0.2.7, Tesseract también participa en el contraste de fragmentos pendientes, además de corroborar separaciones entre palabras. No se descargan modelos durante el uso. El manifiesto elimina explícitamente el permiso `INTERNET`, incluso si alguna dependencia lo declara.
- Dos lecturas locales normales: imagen original y variante con grises/contraste. Se compara el contenido conservando mayúsculas, tildes, cifras, signos, repeticiones y límites entre palabras y códigos: «DE LA» difiere de «DELA». Solo se normalizan Unicode y espacios de formato. Si ambas coinciden y superan los controles, el documento sigue a la comprobación aritmética y la exportación. Las dos lecturas pueden cometer el mismo error.
- Cuando esas lecturas dejan bloqueos recuperables, como desacuerdos o confianza insuficiente, se añade una tercera lectura completa con escala y contraste, siempre que resolución, orientación y geometría permitan el rescate. Las filas todavía no confirmadas se releen primero en cinco franjas temporales: fila aislada en gris y contraste, y contexto de tres filas en color, gris y contraste. Su posición se calcula con la geometría del OCR; no se eligen recortes ni se corrige texto manualmente. Desde 0.2.6 se añaden, cuando hace falta, cuatro vistas con aislamiento horizontal, contexto de dos o tres filas, ampliación y enderezado local. Cada raster idéntico cuenta una sola vez. Hay un máximo de 96 observaciones regionales por página, de las cuales hasta 48 son adicionales; agotar el presupuesto deja las zonas pendientes sin confirmar. La recuperación mediante ML Kit necesita dos lecturas regionales coincidentes, respaldo de una lectura completa y conservación del contenido observado en las lecturas completas. Desde 0.2.7, las zonas que siguen pendientes pueden pasar al contraste entre familias descrito a continuación. Si ninguna ruta confirma todas las zonas, no se genera PDF y el documento conserva el texto y la geometría de la primera lectura completa, sin mezclar recuperaciones parciales.
- En el consenso regional, la confianza se comprueba por fragmento con los elementos que lo componen. Sus umbrales son 0,65 para fragmentos con letras o números y 0,50 para símbolos; son heurísticos, no están calibrados con tus facturas y no equivalen a una probabilidad de exactitud. Las comprobaciones de captura siguen aplicándose aunque una variante ampliada parezca más legible.
- Contraste adicional entre ML Kit, Tesseract y Paddle por regiones de texto. Se elige un candidato literal observado en la misma zona, con respaldo suficiente de al menos dos familias; no se arma una palabra combinando caracteres de salidas distintas. Para contar el respaldo de Paddle se exigen al menos dos variantes con píxeles diferentes, identificadas por SHA-256. Se comprueban también palabras adicionales y cobertura de las lecturas completas; los casos ambiguos quedan pendientes. La asignación geométrica da a cada palabra un solo destino y evita utilizar una palabra fusionada para duplicar una cantidad. Esta ruta está acotada a 128 regiones y a un número limitado de relecturas con contexto; puede rechazar documentos extensos o difíciles. La confianza de cada motor es una heurística: el consenso entre familias también puede equivocarse.
- Recuperación adicional de página con el detector local `PP-OCRv6_small_det`, después de que las rutas anteriores no logren confirmar el documento y conserven cobertura de todo el texto consultado. Propone cuadriláteros desde los píxeles, rectifica líneas completas y las contrasta con ML Kit, el lector latino Paddle y Tesseract. Incluye variantes temporales de iluminación para sombras. Cada cantidad aislada debe tener una posición y cobertura física propias; la presencia de solo parte de una palabra en un recorte no acredita haberla leído completa. Las palabras y cifras pendientes no se rellenan. La aceptación exige conservar también la evidencia consultada por la ruta anterior. Es una ruta experimental: no implica que aumente el número de boletas completas correctas.
- Detección conservadora de un borde visible del papel con OpenCV local, usada solo como evidencia para revisar detecciones independientes que quedaron fuera del texto. Requiere un contorno estable, apoyo de los gradientes de la imagen en sus cuatro lados y que las posiciones de texto conocidas queden dentro. Solo puede descartarse una detección externa si toda su caja está fuera del polígono ampliado por su incertidumbre y una comprobación adicional de Paddle no encuentra una lectura fuerte en esa zona. Si la prueba geométrica o la óptica no bastan, el bloqueo permanece. No recorta el archivo original ni corrige toda la perspectiva.
- Comprobación decimal conservadora de subtotal + impuesto = total, cuando las etiquetas son inequívocas y no aparecen ajustes como descuentos o retenciones. Una suma correcta tampoco demuestra que todo el texto sea correcto.
- Orientación EXIF de la cámara, comprobación de resolución, confianza OCR, páginas vacías y límites de memoria.
- Las fotos inclinadas entre 5° y 45° (con al menos 700 px de lado menor) se enderezan sobre fondo blanco y se vuelven a leer; las filas se ordenan siguiendo la inclinación del papel. Las filas decorativas de asteriscos, que el OCR convierte en «XXXX», se omiten del texto y del PDF, y la app lo indica.
- Las fotos con menos de 700 px de lado menor se leen sobre una ampliación de 2x o 3x (la que deja el lado menor más cerca de 1000 px); las comprobaciones de resolución siguen evaluando la foto original.
- El texto superpuesto con otra orientación (pegatinas giradas, texto vertical en el margen) se maqueta como bloque aparte después del recibo. En el cuerpo, los importes se emparejan en orden con su fila de etiquetas, columna por columna, para que el papel curvado no los desplace a la fila vecina. Al agrupar filas, un fragmento corto cuyo ángulo difiere mucho del de la página no impone su inclinación al emparejamiento, y solo se aparta como texto superpuesto un bloque de al menos dos fragmentos con el mismo ángulo anómalo.
- En las páginas para revisar, cada fragmento se somete a votación entre ML Kit (lectura con contraste), Paddle y Tesseract: solo se sustituye por una lectura completa que coincida en al menos dos familias distintas, sin empates. Los fragmentos sustituidos se resaltan siempre en el PDF.
- PDF de varias páginas: se procesan todas o se informa un fallo; no se exporta silenciosamente solo una parte.
- Fotografías JPEG/PNG/WebP y PDF; otros formatos que Android no pueda decodificar se rechazan. Convierte TIFF o imágenes animadas a PDF para evitar perder páginas.
- Límite por archivo: 40 MB; PDF de hasta 50 páginas. Los trabajos se almacenan con WorkManager y se procesan secuencialmente.
- PDF nuevo A4 con tipografía proporcional, cabecera, tabla con filas alternadas, importes alineados y total destacado. Conserva las posiciones de los fragmentos leídos para reconstruir las columnas. Reconoce también encabezados repartidos en dos renglones y los repite al continuar la tabla en otra página. No replica logotipos ni firmas ni inventa campos ausentes. El contenido transcrito no se cambia a datos de otro emisor.
- Si no se puede interpretar una tabla, conserva el texto en orden legible. Los documentos antiguos sin geometría siguen siendo exportables; guardar un PDF vuelve a componerlo con el diseño vigente. No se rellenan cantidades o importes por cálculo.
- Originales y resultados permanecen en el almacenamiento privado de la aplicación; desinstalarla elimina esos datos. Guarda fuera de la aplicación los PDF que quieras conservar.

Las entradas **Subir foto** e **Importar PDF** pasan por el reconocimiento y sus comprobaciones. Desde 0.2.5 se retiró el área de ejemplo: ya no hay botón ni creación de boletas ficticias en la app, y los ejemplos de diseño antiguos no aparecen en la bandeja. Fotografías borrosas, reflejos, texto cortado, manuscritos, tablas complejas y fuentes pequeñas pueden producir bloqueos o errores no detectados. El detector de papel de 0.2.7 es opcional y limitado: no resuelve todos los bordes, papeles curvados, oclusiones o páginas que salen del encuadre. No hay corrección completa de la perspectiva de la hoja. La ruta adicional rectifica cuadriláteros de líneas; los documentos curvados, incompletos o con geometría ambigua pueden seguir rechazándose. Un PDF listo significa que superó estas comprobaciones, no que se haya demostrado una transcripción perfecta.

## Compilar

Abre `android/` en Android Studio o usa:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

La compilación requiere JDK 17 o posterior, SDK Android 35 y acceso a Internet para descargar dependencias. Esto solo corresponde al desarrollo: la app instalada procesa los documentos sin conexión.

- **Debug** (`assembleDebug`): `android/app/build/outputs/apk/debug/app-debug.apk`. Es depurable, va firmado con la clave de depuración de la máquina e incluye las cuatro ABI (arm64-v8a, armeabi-v7a, x86 y x86_64) para emuladores y pruebas instrumentadas. Pesa unos 180 MB. **No se distribuye.**
- **Release** (`assembleRelease`): un APK por ABI en `android/app/build/outputs/apk/release/`, `app-arm64-v8a-release.apk` para la mayoría de tablets y `app-armeabi-v7a-release.apk` para tablets antiguas de 32 bits. No incluye x86 ni x86_64, no es depurable y se reduce con R8 (reglas en `android/app/proguard-rules.pro`). En 0.2.8 pesan unos 72 MB y 68 MB, con los mismos modelos y bibliotecas nativas que el debug. Sin keystore configurado, los archivos se llaman `…-release-unsigned.apk` y Android no los instala. Si una tablet muestra «App no instalada» con el APK arm64-v8a, es de 32 bits: usa el de armeabi-v7a. Un release lanzado sin nombrarlo (`./gradlew assemble`) genera un único APK con esas dos ABI.

### Firma de release

Se hace una sola vez. Genera tu propio keystore fuera del proyecto; `keytool` (incluido en el JDK) pide la contraseña de forma interactiva:

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore ~/factura-clara-release.jks -alias factura-clara -keyalg RSA -keysize 4096 -validity 10000
```

Copia `android/keystore.properties.example` como `android/keystore.properties` y completa la ruta, el alias y las contraseñas. En PKCS12, la contraseña de la clave es la misma que la del almacén. `keystore.properties`, `*.jks` y `*.keystore` están en `.gitignore`. Si prefieres no guardar contraseñas en un archivo, define las variables de entorno `FACTURA_CLARA_KEYSTORE`, `FACTURA_CLARA_STORE_PASSWORD`, `FACTURA_CLARA_KEY_ALIAS` y `FACTURA_CLARA_KEY_PASSWORD` antes de ejecutar `assembleRelease`.

Haz una copia de seguridad del keystore y de su contraseña. Si se pierden, ninguna actualización podrá instalarse sobre la app ya instalada: habrá que desinstalarla y se perderán los documentos.

### Publicar una versión

1. Sube `versionCode` y `versionName` en `android/app/build.gradle.kts`.
2. Ejecuta `./gradlew testDebugUnitTest assembleRelease` desde `android/`.
3. Comprueba los APK firmados con `apksigner verify --print-certs` (el certificado no debe ser `CN=Android Debug`) y `aapt2 dump badging` (no debe aparecer `application-debuggable`). Ambas herramientas están en `build-tools` del SDK.
4. Instala el APK en una tablet de prueba y procesa al menos una foto y un PDF hasta **PDF listo**. Las pruebas instrumentadas se ejecutan sobre el debug sin R8, así que no cubren el APK reducido.
5. Copia los APK como `output/android/Factura-Clara-<versión>-arm64-v8a.apk` y `output/android/Factura-Clara-<versión>-armeabi-v7a.apk`, y regenera las sumas desde esa carpeta:

   ```bash
   cd output/android && shasum -a 256 Factura-Clara-*.apk > SHA256SUMS.txt && shasum -a 256 -c SHA256SUMS.txt
   ```

6. Actualiza el paso 1 de [Uso](#uso) con la nueva versión y publica junto a los APK la huella SHA-256 del certificado de release.

## Comprobar la meta de 100 facturas

La validación necesita 100 facturas distintas y representativas, junto con transcripciones correctas preparadas fuera de la aplicación. Hay que contar tanto errores como rechazos; bloquear todas las facturas no equivale a acertar.

Para cada documento registra: PDF generado o rechazo, texto exacto o diferencias, cifras incorrectas/omitidas, disposición de productos y tiempo de proceso. Conserva parte del conjunto sin usar durante los ajustes. La meta es medible, pero no permite prometer exactitud universal en documentos futuros.

Las pruebas automatizadas del proyecto comprueban exportación sin pérdida de texto, bloqueo ante problemas conocidos y lectura real del modelo incluido en modo sin conexión. No sustituyen ese conjunto de facturas reales.

## Verificación de la entrega 0.1.0

- Compilación del APK y análisis Android Lint completados sin errores; quedan avisos no bloqueantes.
- 26 pruebas unitarias y 12 pruebas de instrumentación aprobadas en un emulador de tablet con Android 15, modo avión y Wi-Fi desactivado.
- OCR real de la muestra ficticia, generación automática y guardado mediante el selector de Android verificados en aquella versión. Esa prueba no medía los límites entre palabras. El PDF guardado coincide byte por byte con el resultado generado.
- PDF de ejemplo inspeccionado visualmente y texto seleccionable comprobado: `output/pdf/ejemplo-boleta-digital.pdf`.
- Captura de la interfaz: `output/qa/tablet.png`. Suma SHA-256 del instalador: `output/android/SHA256SUMS.txt`.
- Pendiente: probar cámara, rendimiento y almacenamiento en tu tablet física, y medir errores y rechazos en 100 facturas reales diferentes. Estas pruebas no acreditan transcripción perfecta.

## Rediseño 0.2.0 y referencias reales

Se inspeccionaron boletas publicadas en informes de la Universidad Nacional de Juliaca: los formatos SOL, ticket y comercial de las páginas 106–108 del [informe 2025](https://portal.unaj.edu.pe/sites/default/files/proyeccion-social-informes2025/Wi%C3%B1ariypaq%20hukllachasqa.pdf), y el comprobante extenso de la página 83 del [informe 2024](https://www.portal.unaj.edu.pe/sites/default/files/proyeccion-social-informes2024/5.%20IMFORME%20FINAL%20GRUPO%20CURTIENDO%20SEFEX.pdf). También se consultó el [material de SUNAT](https://cultura.sunat.gob.pe/sites/default/files/2020-09/Ficha_PS_5y6Primaria.pdf). Se usaron como referencias de estructura y pruebas de entrada, sin copiar sus identidades al ejemplo.

Las dos páginas reales SOL y comercial se procesaron por el flujo normal de importación y OCR: **ambas terminaron en `needs_recapture`**, por confianza muy baja y discrepancias entre lecturas. Una prueba diagnóstica que confirma ese rechazo no cuenta como transcripción correcta. Esas imágenes proceden de un informe; no son originales de cámara. Todavía falta mejorar y medir el comportamiento con las facturas del usuario.

El nuevo ejemplo de diseño es `output/pdf/boleta-redisenada.pdf`. Sus datos y posiciones son ficticios y conocidos; prueba la composición del PDF, no la exactitud del OCR. Hay otra muestra de 70 productos para verificar paginación y conservación del texto. Las pruebas de reconocimiento de la imagen se ejecutan por separado. Los documentos públicos de investigación están únicamente en los recursos del APK de pruebas, nunca en el APK entregado al usuario.

La lectura de la muestra compleja también mostró una limitación: una variante del OCR omitió una cantidad aislada. Se probaron grises, cambios tonales y varias escalas sin obtener una segunda lectura exacta. Se conservó el rechazo; no se relajaron los controles ni se sustituyó ese resultado por el PDF de diseño. Un control sintético simple prueba por separado el recorrido completo de reconocimiento y exportación.

Además, la comprobación aritmética distingue base imponible de subtotal completo cuando aparecen operaciones exoneradas/inafectas, y total del comprobante de saldo por pagar cuando hay anticipos. Mantiene el bloqueo de totales contradictorios del mismo tipo y moneda.

Verificación final de 0.2.0: compilación y Lint sin errores, 44 pruebas unitarias y 19 pruebas en Android 15 aprobadas sin conexión. Estas incluyen rechazos esperados; no son 63 facturas transcritas correctamente. La prueba positiva simple de aquella versión recorría el OCR real y la exportación, pero su comparación eliminaba todos los espacios. La revisión 0.2.1 encontró una unión de palabras que esa prueba no detectaba; ya no se cuenta como transcripción exacta. El PDF de cinco productos ocupa una página; el de 70 productos, cuatro, con cada producto una sola vez, columnas repetidas y todos los importes conservados. Se inspeccionaron visualmente todas las páginas. Registro Android: `output/qa/v2/android-tests-final.txt`.

## Precisión y entrada de fotos: 0.2.1

La entrada por Cámara y Agregar archivos sí reconoce los píxeles originales; la referencia escrita de las pruebas no entra en el motor. El botón de ejemplo mantiene su función exclusiva de mostrar el diseño.

- La decodificación conserva más detalle dentro del presupuesto de memoria: para una foto de 4000 × 3000 y un presupuesto de 10 millones de píxeles, antes se reducía a 2000 × 1500 (3 millones); ahora se aproxima a 10 millones. Se controla también la memoria temporal de la decodificación y se mantienen la orientación EXIF y los límites de tamaño. Más resolución retenida no garantiza una lectura exacta.
- La comparación entre lecturas ya detecta palabras o códigos unidos/separados. Conserva mayúsculas, tildes, signos y dígitos, además de comprobar las cantidades por separado.
- Se añadió una evidencia reproducible imagen → importación real → OCR → PDF, con el texto de referencia usado únicamente después del reconocimiento para comparar. Es un control sintético claro, no una fotografía de cámara ni una prueba de exactitud en facturas reales.
- Se añadió una referencia visual de la boleta pública SOL, extraída como imagen original de 729 × 543 del informe. Se registra si cada bloque o campo coincide, difiere o no puede asignarse de forma inequívoca. La referencia fue leída visualmente por un asistente; aún no está doblemente validada por una persona. Se cuentan omisiones y rechazos, y no se publica un porcentaje de «perfección» a partir de una sola boleta.
- Los ensayos de lectura por franjas no confirmaron todos los caracteres de la muestra compleja. Quedaron como diagnóstico y no se incorporaron al motor ni se relajaron los bloqueos.

Verificación 0.2.1: 53 pruebas unitarias y 25 pruebas Android aprobadas sin conexión, compilación y Lint sin errores. Incluyen rechazos y diagnósticos; esos números no son facturas transcritas correctamente. El control original que unía palabras se conserva como regresión y ahora exige rechazo. Otro control sintético con espacios visuales ampliados sí completó el flujo real y coincidió en caracteres, palabras, signos y cantidades; está marcado como control ajustado y no valida fotografías reales.

El ensayo de resolución mantuvo 9.996.438 píxeles frente a 3.000.000 y redujo las ediciones de caracteres de 11 a 7 sobre la referencia conocida, pero ambas lecturas siguieron siendo inexactas. Es una comparación de una pasada en una imagen sintética, no una tasa de precisión de la aplicación.

La boleta pública original continuó en `needs_recapture`: coincidieron 11 de 35 bloques y 7 de 15 campos críticos con la referencia visual; 2 bloques y 1 campo tenían asociación espacial no resoluble. Las regiones no resueltas cuentan contra la exactitud; no se presentan como aciertos. Sigue pendiente una evaluación con fotos originales del usuario y 100 facturas representativas. Resultados y metodología: `output/qa/v3/ground-truth/accuracy-result.json`; registro Android: `output/qa/v3/android-tests-final.txt`.

## Carga de fotos ya tomadas: 0.2.2

**Subir foto** es la entrada principal y abre el selector de imágenes locales de Android. Admite selección múltiple, con un documento por archivo. **Importar PDF** abre un selector separado para PDF. Se retiró la captura directa desde la app; ante un problema, la acción ofrece elegir otra foto o PDF según el documento. Una importación exitosa selecciona la primera imagen recién agregada, para mostrar su resultado en lugar de mantener visible un documento anterior. El selector solicita archivos locales y conserva los bytes originales al copiarlos.

Este cambio simplifica la entrada; no garantiza mayor exactitud por sí mismo ni modifica el motor de OCR o sus comprobaciones. Se mantiene el procesamiento sin conexión y el PDF aprobado anteriormente.

Verificado en Android 15 sin conexión: compilación y Lint sin errores, apertura del selector con filtro de imágenes, importación real mediante URI de contenido, selección automática del documento recién agregado y resultado OCR/PDF del control claro. El archivo copiado coincide byte por byte con la imagen elegida. También se comprobó el selector separado de PDF y su cancelación. Evidencia: `output/qa/v4/upload-verification.json` y `output/qa/v4/tablet.png`. Esta prueba de carga usa un control sintético; no acredita transcripción perfecta de fotos reales.

## Lecturas regionales y fotos reales: 0.2.4

Esta versión mantiene las dos lecturas normales e incorpora una tercera lectura y rescate regional solo ante bloqueos recuperables. Una entrada que ya supera los controles normales conserva su recorrido de exportación. Si el rescate no se completa, permanece la primera lectura completa como resultado rechazado. Las variantes se crean en memoria a partir de la imagen importada; el archivo original permanece intacto y ningún texto de referencia entra en el reconocimiento. Se mantiene el modo oscuro de fábrica incorporado en 0.2.3 y el flujo **Subir foto → procesamiento → PDF**, sin editor.

Se corrigieron los votos de orientación para tratar +180° y −180° como el mismo giro. El texto y el PDF comparten la reconstrucción de filas, que utiliza la inclinación de las líneas para mejorar la asociación entre columnas. La confianza regional se calcula con los elementos de cada fragmento, sin heredar la de números situados en otra columna. Cuando se activa el rescate, el consenso impide ocultar texto adicional observado por la tercera lectura y bloquea si falta su geometría o queda texto completo sin asignar. Estas medidas reducen fallos concretos, pero no garantizan que producto y precio se asocien correctamente en cualquier papel curvado o fotografiado con perspectiva. Los PDF de la ruta normal de dos lecturas no pasan por esta tercera observación.

El lote de entrega procesó **22 fotos reales de tickets pequeños**, mediante importación, WorkManager y el OCR de producción, sin editar imágenes ni corregir transcripciones. Completó los 22 documentos sin cierres inesperados ni fallos de los controles de integridad del lote en **193.264 ms, aproximadamente 3 minutos y 13 segundos**:

- **1 PDF generado:** `08_pliegues_y_contraste.jpg`. Su transcripción guardada coincidió con la referencia visual independiente en tokens ordenados, signos y cifras; se permiten diferencias de espacios de presentación. No es el ejemplo ficticio de diseño.
- **21 documentos rechazados**, sin generar PDF. El rechazo no se cuenta como transcripción correcta.
- Se prepararon referencias visuales para 6 fotos. Una contiene una ambigüedad sin resolver; entre las otras 5 hubo 1 coincidencia exacta y 4 transcripciones inexactas. Las 16 fotos sin referencia y la referencia ambigua permanecen como **17 casos no evaluables**, dentro del total de 22. No se estima una precisión general a partir del único caso exacto.

Las referencias se utilizaron únicamente después del reconocimiento para evaluar su salida. Fueron elegidas por legibilidad, no por muestreo aleatorio. La comparación mide el texto OCR guardado; la conservación del texto y la presentación del PDF requieren su propia revisión. Este lote tampoco valida todavía las facturas en hoja A4 ni una tablet física.

Evidencia de entrega: `output/qa/real-receipts/delivery/`, con originales, transcripciones, estados y el PDF generado; resumen del procesamiento en `output/qa/real-receipts/delivery/summary.json` y comparación con referencias en `output/qa/real-receipts/delivery-comparison.json`. La carpeta `output/qa/real-receipts/final/` y `final-comparison.json` conservan una prueba anterior a los últimos ajustes. Los ensayos de variantes completas y franjas son diagnósticos separados; una mejora en una región no se presenta como una transcripción completa correcta.

Verificación de 0.2.4: compilación y Android Lint aprobados, 64 pruebas unitarias aprobadas y 21 casos distintos de instrumentación aprobados entre la última ejecución y la repetición de la prueba de geometría. Esta última compara exactamente el texto con las filas reconstruidas desde los fragmentos, y mantiene la comprobación independiente de caracteres impresos y del rechazo esperado. Registros: `output/qa/real-receipts/android-delivery.txt` y `output/qa/real-receipts/android-geometry-regression.txt`. Los casos incluyen rechazos y diagnósticos; no representan 85 facturas transcritas correctamente.

SHA-256 del APK 0.2.4 utilizado en el lote de entrega: `7c4252b0efb5b3b091d3760ba95311c09e45ab34af307bbc80c6ec985bee48dc`.

## Retirada del ejemplo: 0.2.5

Se eliminó **Ver diseño de ejemplo** y la ruta que generaba una boleta con datos ficticios. Los ejemplos de diseño guardados por versiones anteriores quedan fuera de la bandeja, sin afectar las fotos importadas. El generador de muestras se conserva únicamente en el código de pruebas y ya no forma parte del APK de la aplicación. Se mantienen **Subir foto**, **Importar PDF**, el modo oscuro y el procesamiento OCR existente.

## Lectura local y filas inclinadas: 0.2.6

Se reemplazó la asociación codiciosa de columnas por asociaciones globales según su ajuste geométrico. Los fragmentos cortos aportan menos peso al ángulo y todos los miembros de una fila deben ser compatibles. Esto corrige el caso del importe `$0.70` que acababa junto al complemento anterior o siguiente en una foto inclinada, sin cambiar ningún carácter.

Las regiones difíciles cuentan con cuatro observaciones adicionales y transformación inversa a coordenadas de la foto. Los propietarios de los fragmentos se calculan una sola vez por lectura, y el presupuesto de relecturas queda acotado. Cancelar un trabajo interrumpe la espera de ML Kit y la lectura nativa independiente; el resultado cancelado no se publica como PDF listo.

Se incorporó Tesseract4Android 4.9.0 con modelos `tessdata_best` inglés y español, incluidos en el instalador y verificados mediante SHA-256. Es un corroborador limitado de separaciones entre palabras: necesita un candidato realmente observado en al menos dos regiones ML fiables, los mismos caracteres y cifras en todas las lecturas completas, y respaldo espacial del lector independiente. No sustituye letras, no corrige cantidades y no utiliza un texto de referencia o diccionario personalizado. Las discrepancias fiables de otras regiones bloquean la recuperación. Si se consulta el segundo lector, las diferencias de separación detectadas en otras filas tampoco se ocultan.

Los diagnósticos confirmaron que Tesseract por sí solo no mejora las páginas completas del conjunto. Los dos modelos y las variantes pueden compartir errores; estas comprobaciones no garantizan perfección. En documentos rechazados se conserva la primera lectura completa y no se exporta una mezcla de recuperaciones parciales.

Dependencias y modelos: `android/app/src/main/assets/licenses/` y `android/app/src/main/assets/tessdata/MODELS.json`. Las referencias visuales, imágenes de prueba y diagnósticos permanecen fuera de los recursos de producción.

Validación de entrega: **83 pruebas unitarias**, **19 casos de instrumentación** y **1 prueba del lote real** aprobados, compilación y Lint sin errores. Los casos de instrumentación comprueban la lectura del modelo independiente incluido, el mapeo inverso, la cancelación de un trabajo en ejecución y que el siguiente documento pueda completarse. No equivalen a 103 facturas correctas.

Se repitieron las **22 fotos originales** en modo avión, con Wi-Fi desactivado y sin permiso `INTERNET`. Los 22 archivos importados conservaron su SHA-256. Resultado: **1 PDF generado y 21 rechazos**; todavía no aumentó el número de documentos aceptados respecto de 0.2.4/0.2.5. La boleta inclinada 17 pasó de 9 diferencias de tokens a 1, con el importe de 0.70 en su fila correcta; persiste una confusión entre cero y letra O, por lo que sigue rechazada. La foto 15 pasó de 11 diferencias a 5. En documentos rechazados estos son avances de la primera lectura completa, no PDF correctos.

La comparación posterior utiliza ahora 10 referencias visuales: 4 contienen regiones ambiguas o texto cortado y 12 fotos carecen de referencia. Se estableció 1 coincidencia exacta, 5 inexactas y 16 no evaluables dentro del total de 22. Las regiones intranscribibles también cuentan como ambigüedad; no se omiten para afirmar exactitud. El PDF de la muestra 08 conserva los tokens y cifras del OCR y se revisó visualmente.

Tiempo del lote: **262.011 ms (4 min 22 s)** frente a **193.264 ms (3 min 13 s)** antes. Las relecturas adicionales aumentan el tiempo de estas fotos difíciles; la reducción de cálculos repetidos no se presenta como una mejora de velocidad total. El APK universal ocupa 101.742.044 bytes al incluir el segundo lector y ambos modelos.

Evidencia: `output/qa/ocr-v026/release-report.json`, `delivery/summary.json`, `delivery-comparison.json`, `baseline-with-current-references.json`, `android-checks.txt` y `pdf-check/pdf-audit.json`. Los 22 documentos de este lote quedaron archivados de forma reversible, con índice `output/qa/ocr-v026/archived-batch-documents.json`. La app quedó abierta en modo oscuro, sin el área de ejemplo y con la demostración real anterior visible.

APK instalado y evaluado: `output/android/Factura-Clara-0.2.6.apk`; SHA-256 `e064cbf6478efe63a91abd85ecf98dcc67f97a1a729119b959f523c9f4ba036b`.

## Contraste entre familias y fondo externo: 0.2.7

Se mantiene el recorrido inicial de dos lecturas de ML Kit. Cuando falla y la captura permite recuperarlo, continúan la tercera lectura y las comprobaciones regionales anteriores. Si todavía quedan zonas pendientes, se consulta el lector latino de PaddleOCR incluido en el APK y se comparan sus observaciones con ML Kit y Tesseract. Las páginas que ya pasan la ruta normal no se someten automáticamente a los tres motores.

Paddle lee recortes calculados desde la geometría detectada, con variantes de margen y enderezado local. Trabaja con los píxeles de la imagen importada; no recibe transcripciones de referencia, correcciones por nombre de archivo ni diccionarios preparados para estas fotos. Conserva por separado el texto y la confianza de cada variante. Los píxeles repetidos no aportan votos adicionales. Su modelo y alfabeto se verifican mediante SHA-256; los modelos de Tesseract mantienen la misma comprobación.

El consenso admite un fragmento completo realmente observado y respaldado por al menos dos familias con los umbrales establecidos. Puede sustituir una lectura por otra corroborada de esa zona, pero no calcula letras, cantidades o precios ausentes. Revisa la cobertura de las lecturas completas, protege los tokens adicionales detectados y mantiene el rechazo ante candidatos rivales o respaldo insuficiente. Las palabras se asignan por posición e inclinación a una única región; una asignación ambigua o una palabra fusionada que atraviesa dos celdas no se reparte para aparentar coincidencia. Los umbrales de los lectores son distintos y no representan probabilidades comparables de exactitud. El consenso también conserva la agrupación numérica literal: «2 / 60.00» y «2/60.00» no se corroboran entre sí, aunque sus caracteres coincidan. El espacio entre un símbolo monetario y su importe sí puede cambiar si no altera los grupos numéricos.

La detección de papel propone polígonos a partir de los píxeles y usa las posiciones OCR solo para vetar un borde que excluya texto conocido. Exige estabilidad entre varios umbrales, apoyo suficiente en los cuatro lados, separación del marco de la foto y un margen de incertidumbre. No utiliza la envolvente del texto como supuesto borde del papel. Una detección independiente solo se trata como fondo si su caja completa queda fuera de ese margen y la lectura óptica adicional tampoco confirma texto fuerte. En caso de borde ambiguo, cortado, oculto o fallo del detector, no se descarta la observación por esa vía. Esta comprobación no demuestra por sí sola que la transcripción del interior esté completa.

Los nuevos lectores se ejecutan sin conexión, con límites de regiones, tamaño y memoria. La recuperación adicional se intenta únicamente si el documento completo cabe en 64 celdas y no hay texto completo sin asignar; la decisión se toma antes de cargar los lectores adicionales. Si excede ese presupuesto se conserva el resultado regional anterior, sin truncar la página ni publicar una parte. La cancelación solicita terminar la inferencia nativa y los recursos se liberan desde el hilo que los utiliza; la imagen original no se modifica ni se recicla mientras un lector la necesita. Los casos difíciles requieren más trabajo y pueden tardar más. La aceptación, los errores y los rechazos deben medirse con el lote completo y con documentos nuevos; estas mejoras no acreditan todavía la meta de 100 facturas ni el comportamiento en una tablet física.

Antes de exportar se revisa además la estructura que usará el PDF. En una tabla con encabezado reconocido, una fila de importes sin descripción asociada bloquea la exportación. No se decide por suposición a qué producto corresponde un importe separado.

Modelo y licencias: `android/app/src/main/assets/paddle/MODELS.json`, `android/app/src/main/assets/tessdata/MODELS.json` y `android/app/src/main/assets/licenses/`. Las fotos, referencias y diagnósticos de evaluación permanecen fuera de los recursos de producción.

### Resultado y límites de esta entrega

El lote de 22 fotos originales produjo **2 PDF verificados**, correspondientes a `08_pliegues_y_contraste.jpg` y `17_foto_girada.jpg`, frente a 1 en 0.2.6. Hubo 19 rechazos y un documento que excedió los 180 segundos de espera del ensayo. **Las otras 20 fotos no cuentan como transcripciones correctas.** Los 22 originales conservaron sus bytes; no se editaron fotos ni se corrigió el texto manualmente.

Las 14 referencias visuales disponibles permiten evaluar completamente 6 documentos: 2 coincidieron y 4 presentaron diferencias. Los otros 16 casos (8 referencias con ambigüedades y 8 sin referencia) siguen sin una evaluación completa. Las referencias se prepararon mediante revisión visual asistida, sin doble validación humana, y solo se utilizaron para evaluar la salida. Los PDF de las muestras 08 y 17 se extrajeron y renderizaron por separado: conservan palabras, signos e importes de sus referencias, incluido `$0.70` junto a `E Green Chile` en la muestra 17. Esto no demuestra exactitud general ni valida aún el conjunto A4.

El lote tardó **1.333.430 ms (22 min 13 s)**, frente a 262.011 ms (4 min 22 s) en 0.2.6. El aumento de comprobaciones hace esta versión considerablemente más lenta en las fotos difíciles. Después del lote se añadió un límite previo de 64 celdas completas y se corrigió una entrada demasiado estrecha al lector nativo, además de reforzar la agrupación numérica y la asociación de importes. La muestra 22 terminó rechazada en 27.594 ms en una prueba posterior; la muestra 09 dejó de registrar la excepción nativa, pero continuó rechazada. La muestra 17 volvió a generar un PDF exacto en la repetición dirigida.

**El lote completo no se repitió con el APK final.** Se conserva el APK del lote y se documentan por separado los cambios posteriores, las repeticiones dirigidas y la demostración desde la interfaz. No se atribuye al APK final una medición nueva de velocidad sobre las 22 fotos.

Verificación final de compilación: 129 pruebas unitarias aprobadas; Android Lint, 0 errores y 27 advertencias. La instrumentación se ejecutó por etapas: una primera serie detectó tres fallos de regresión, que se corrigieron y repitieron; una invocación posterior del diagnóstico tenía un argumento inválido y se repitió con el argumento correcto. Las dos últimas comprobaciones Android pasaron. Los registros conservan también los fallos originales; las pruebas de software no se cuentan como facturas transcritas correctamente.

Evidencia: `output/qa/ocr-v027/delivery/summary.json`, `delivery-comparison.json`, `batch-pdf-check/summary.json`, `batch-to-final-changes.json`, `build-checks.json`, `android-final-checks.txt`, `android-corrections.txt` y `android-last-regression.txt`. Los 30 documentos creados exclusivamente para estas pruebas quedaron archivados de forma reversible; los 8 documentos anteriores se conservaron, incluidos los ejemplos antiguos ocultos.

APK final: `output/android/Factura-Clara-0.2.7.apk`, 168.984.577 bytes; SHA-256 `4a4fa4ee0ba1dad004402881573fdc62757ad3a85a3ee0ca4c66a5890b800a47`. Se comprimen los archivos nativos del instalador sin alterar los modelos ni la resolución de las fotos.

## Estructura

- `android/app/src/main/java/com/facturaclara/app/`: aplicación, almacenamiento, OCR y PDF.
- `android/app/src/test/`: pruebas de comprobaciones y composición de texto.
- `android/app/src/androidTest/`: pruebas reales de OCR, PDF y bloqueos en Android.
- `prototypes/macos/`: prototipo inicial anterior a concretar Android; no es necesario para la aplicación.

Referencia: [ML Kit con modelo incluido](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

### Demostración desde la interfaz de la tablet

Se cargó `08_pliegues_y_contraste.jpg` mediante **Subir foto**, se esperó la lectura automática y se guardó el PDF mediante el selector de Android. La foto importada conserva exactamente los bytes originales y el PDF exportado coincide byte por byte con el generado por la app. La prueba se hizo en modo avión y con Wi-Fi desactivado; no hubo corrección manual ni texto de referencia suministrado al motor.

El PDF de esta muestra coincide con la referencia visual en palabras, signos y cifras (60 tokens, 172 caracteres sin blancos). Se verificó también el render completo. Esto no acredita exactitud en otras facturas.

- PDF entregable: `output/pdf/demostracion-boleta-real.pdf`.
- Grabación sin editar: `output/qa/real-receipts/ui-demo/demostracion.mp4`.
- Evidencia de carga y exportación: `output/qa/real-receipts/ui-demo/ui-verification.json` y `pdf-audit.json` en la misma carpeta.
- En **Descargas** de la tablet: `08_pliegues_y_contraste_digital.pdf` y `Demostracion_Factura_Clara.mp4`.

Los 88 documentos creados exclusivamente por los cuatro lotes de pruebas quedaron archivados de forma reversible dentro de `files/qa-archive/` de la app, conservando sus originales y resultados. El índice es `output/qa/real-receipts/archived-batch-documents.json`. Los documentos ajenos a esos lotes se conservaron en la bandeja.

### Nueva demostración real: boleta inclinada

Con el APK final 0.2.7 instalado se cargó `17_foto_girada.jpg` desde **Subir foto** y se guardó `17_foto_girada_digital.pdf` en **Descargas** de la tablet. Se hizo en modo avión, con Wi-Fi desactivado, sin edición de la imagen ni del texto. La copia importada coincide byte por byte con el original; el PDF guardado coincide con el generado por la app.

El PDF de una página se extrajo y renderizó: coincide con la referencia visual en 92 tokens, 240 caracteres sin blancos y 8 importes. Se comprobó la asociación de `$0.70` con `E Green Chile`. Se permiten diferencias de espacios de presentación, sin unir grupos numéricos distintos.

- PDF: `output/pdf/demostracion-boleta-inclinada.pdf`.
- Evidencia de la interfaz y exportación: `output/qa/ocr-v027/ui-demo/`.
- Revisión del PDF: `output/qa/ocr-v027/ui-demo/pdf-check/qa.json`.
- Informe completo con el alcance de las pruebas y las limitaciones: `output/qa/ocr-v027/release-report.json`.

La app queda abierta en modo oscuro con esta foto y su texto. Se conservan los 8 documentos previos y hay 6 visibles en la bandeja, incluidos controles antiguos; el contador de la interfaz no representa el resultado del lote real de 22 fotos.
