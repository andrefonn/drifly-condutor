# DriFly Condutor

App Android para motoristas de aplicativo (corridas): lê as ofertas na tela, mostra o ganho por km,
controla despesas do dia e trabalha com mapa offline (MapLibre).

## Estado atual (fase de desenvolvimento)

| Item | Situação |
| --- | --- |
| `applicationId` | `com.drifly.condutor` |
| Nome exibido | DriFly Condutor |
| Login / conta | não existe nesta fase |
| Firebase / Firestore / Billing | removidos |
| Banco | SQLite local (`DatabaseHelper`), nenhum banco remoto |
| Mapa colaborativo | sem servidor: a tela avisa que está offline e não desenha pinos |
| OCR de imagem | desligado (motor anterior removido); ver `LeitorDeTela` |
| Baixa de mapas offline | aponta para este repositório; os pacotes ainda não foram publicados |

## Requisitos

- JDK 21
- Android SDK com `compileSdk 36` (o caminho fica em `local.properties`)
- Gradle vem pelo wrapper (`gradlew.bat`)

## Compilar

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

O APK sai em `app/build/outputs/apk/debug/`.

## Assinar para release

Ver [SIGNING.md](SIGNING.md). A keystore e as senhas NÃO vão para o Git.

## Estrutura

```
app/src/main/java/com/drifly/condutor/
  MainActivity.kt            painel do dia, despesas, Apoiar
  MapFragment.kt             mapa MapLibre + pacotes offline
  RotaAccessibilityService.kt leitura das ofertas na tela
  MonitoringService.kt       monitoramento em segundo plano
  FuelBubbleService.kt       bolinha flutuante com velocímetro
  DatabaseHelper.kt          SQLite local
  map/                       motor de rota, catálogo de regiões, estilos
app/src/main/res/            layouts, ícones, temas
```

## Identidade de assinatura

- SHA-1: `D2:FA:CC:B4:4F:8F:DF:18:73:AE:84:4C:A6:4C:D6:5B:A0:8C:61:02`
- SHA-256: `6F:18:A5:3D:E0:93:3A:68:CB:FA:6C:8F:87:8D:7B:A2:3D:65:49:F8:9C:F7:85:DF:2F:31:FF:83:F0:57:B4:D1`
