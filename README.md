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
| Mapa | online pelo OpenFreeMap quando não há pacote; offline (PMTiles) quando o pacote está instalado |
| Mapa colaborativo | a camada de pinos existe, mas ainda não tem servidor: fica vazia |
| OCR de imagem | ativo com ML Kit Text Recognition embutido (funciona sem internet) |
| Baixa de mapas offline | publicada na release [`map-packs-v1`](https://github.com/andrefonn/drifly-condutor/releases/tag/map-packs-v1) deste repositório |

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

## Mapa e pacotes offline

O mapa é híbrido: dentro de um pacote instalado ele desenha o vetor local (PMTiles via
`LocalMapTileServer`); fora dele usa o OpenFreeMap online, sem chave. O aviso de download só
aparece quando a região tem pacote publicado.

Os arquivos ficam na release `map-packs-v1` deste repositório:

| Pacote | Tamanho | Área |
| --- | --- | --- |
| `joinville-pack-v1.zip` | 28 MB | Joinville e região (SC) |
| `pa-pack-v1.zip` | 113 MB | Pará |

`catalog.json` (catálogo das regiões) e `manifest.json` (formato antigo) também estão na release.
Para publicar um pacote novo, use `scripts/map-pack/` e envie os arquivos para a mesma release.

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
