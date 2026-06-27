# PC2 CampusSpace - Pregunta 1 lista para ejecutar

Este paquete deja preparada la Pregunta 1: compilación Maven, ejecución local fuera del IDE, pruebas de endpoints, scripts Python y ejecución con Docker Compose.

## Estructura

```text
CampusSpace_PC2_P1_Listo/
├── source-service/
│   ├── Dockerfile
│   ├── pom.xml
│   ├── src/main/java/edu/upc/campusspace/source/SourceServer.java
│   ├── src/main/resources/salas.csv
│   ├── src/main/resources/rutas.txt
│   ├── src/main/resources/template.html
│   └── target/campusspace-source.jar
├── composer-service/
│   ├── Dockerfile
│   ├── pom.xml
│   ├── src/main/java/edu/upc/campusspace/composer/ComposerServer.java
│   ├── src/main/resources/composer-template.html
│   └── target/campusspace-composer.jar
├── 05_python/
│   ├── probe_components.py
│   └── routes_audit.py
├── docker-compose.yml
├── rutas.txt
├── 01_compilar_maven.cmd
├── 01B_compilar_maven_con_docker.cmd
├── 02_run_source.cmd
├── 03_run_composer.cmd
├── 04_test_endpoints.cmd
├── 05_docker_up.cmd
├── 06_docker_test.cmd
└── 07_python_tests.cmd
```

## Requisitos

- Java 17 o superior.
- Docker Desktop iniciado.
- Maven instalado, o usar `01B_compilar_maven_con_docker.cmd` para compilar con Maven dentro de Docker.
- Python 3 para ejecutar los scripts.
- VS Code para abrir la carpeta y tomar capturas.

## Flujo recomendado para la Pregunta 1

### 1. Abrir en VS Code

1. Extrae el ZIP.
2. Abre VS Code.
3. Selecciona `File > Open Folder...`.
4. Abre la carpeta `CampusSpace_PC2_P1_Listo`.

Captura requerida: estructura visible del proyecto en el explorador de VS Code.

### 2. Revisar rutas externas

Abre `rutas.txt` en VS Code.

Captura requerida: `rutas.txt` mostrando los 6 sitios externos.

### 3. Compilar Maven

Opción A, si tienes Maven instalado:

```bat
01_compilar_maven.cmd
```

Opción B, si NO tienes Maven instalado pero sí Docker Desktop:

```bat
01B_compilar_maven_con_docker.cmd
```

Captura requerida: terminal mostrando `BUILD SUCCESS` para `source-service` y `composer-service`.

### 4. Levantar Source fuera del IDE

Abre una terminal CMD en la raíz del proyecto y ejecuta:

```bat
02_run_source.cmd
```

Debe mostrarse:

```text
CampusSpace Source Service iniciado en puerto 4570
Endpoints: /health, /salas, /reservas, /html, /rutas
```

Captura requerida: terminal del Source levantado fuera del IDE.

### 5. Levantar Composer fuera del IDE

Abre otra terminal CMD en la raíz del proyecto y ejecuta:

```bat
03_run_composer.cmd
```

Debe mostrarse:

```text
CampusSpace Composer Service iniciado en puerto 4571
SOURCE_SERVICE_URL=http://localhost:4570
```

Captura requerida: terminal del Composer levantado fuera del IDE.

### 6. Probar endpoints locales

Abre una tercera terminal CMD y ejecuta:

```bat
04_test_endpoints.cmd
```

También puedes abrir en navegador:

```text
http://localhost:4570/health
http://localhost:4570/salas
http://localhost:4570/reservas
http://localhost:4570/rutas
http://localhost:4570/html
http://localhost:4571/debug-config
http://localhost:4571/reservas-compuestas
http://localhost:4571/dashboard
http://localhost:4571/dashboard-html
```

Capturas requeridas:

- respuesta de `/health` del Source;
- respuesta de `/salas` o `/reservas`;
- respuesta de `/rutas`;
- respuesta de `/debug-config` del Composer;
- respuesta de `/dashboard` o `/dashboard-html`;
- terminales mostrando mensajes por cada request recibido.

### 7. Ejecutar scripts Python

Con Source y Composer todavía levantados, ejecuta:

```bat
07_python_tests.cmd
```

Capturas requeridas:

- salida de `probe_components.py` validando Source y Composer;
- salida de `routes_audit.py` mostrando dominios externos.

### 8. Levantar con Docker Compose

Detén los servicios locales primero con `CTRL + C` en las terminales del Source y Composer.

Luego abre Docker Desktop y verifica que esté corriendo.

Desde una terminal CMD en la raíz del proyecto ejecuta:

```bat
05_docker_up.cmd
```

Esto ejecuta:

```bat
docker compose up --build
```

Capturas requeridas:

- `source-service/Dockerfile` abierto en VS Code;
- `composer-service/Dockerfile` abierto en VS Code;
- `docker-compose.yml` abierto en VS Code;
- terminal mostrando build y contenedores levantados;
- Docker Desktop mostrando los contenedores `campusspace-source` y `campusspace-composer` activos.

### 9. Probar endpoints en Docker

Deja la terminal de Docker Compose abierta. Abre otra terminal CMD y ejecuta:

```bat
06_docker_test.cmd
```

La captura más importante es:

```text
http://localhost:4571/debug-config
```

Debe responder:

```json
{"SOURCE_SERVICE_URL":"http://campusspace-source:4570","requiredForDockerNetwork":true}
```

Esto evidencia que Composer consume al Source por nombre de servicio Docker y no por `localhost` dentro del contenedor.

## Explicación para el informe

En ejecución local, el Composer usa `SOURCE_SERVICE_URL=http://localhost:4570` porque ambos procesos se ejecutan en la misma máquina. En Docker Compose, `localhost` dentro del contenedor del Composer apuntaría al propio contenedor del Composer, no al Source. Por eso se configura `SOURCE_SERVICE_URL=http://campusspace-source:4570`, usando el nombre del servicio definido en `docker-compose.yml` dentro de la red interna `campusspace-net`.

Los sitios de `rutas.txt` se consideran sistemas externos para el diagrama C4. El script `routes_audit.py` solo audita las URLs y dominios; no reemplaza a los servicios Java ni crea una base de datos.
