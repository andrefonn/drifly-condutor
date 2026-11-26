# DriFly Condutor — mapa do projeto

Documento curto do estado real do código nesta fase. O que ainda não existe está marcado
como pendente em vez de descrito como pronto.

## Stack

- Android nativo em Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1
- `compileSdk`/`targetSdk` 36, `minSdk` 29
- Material 3 + AppCompat (sem Google Play Services)
- MapLibre Native 12.3.1 (mapa offline)
- WorkManager, AndroidX Biometric, kotlinx-coroutines
- SQLite local (`DatabaseHelper`)

## Módulos

| Arquivo | Papel |
| --- | --- |
| `MainActivity.kt` | painel do dia, despesas, abas, Apoiar/PIX, diagnóstico |
| `MapFragment.kt` | mapa MapLibre, catálogo de regiões, baixa de pacotes offline |
| `RotaAccessibilityService.kt` | lê a tela dos apps de corrida (árvore de acessibilidade) |
| `MonitoringService.kt` | serviço de monitoramento e notificações |
| `FuelBubbleService.kt` | bolinha flutuante (velocímetro, menu rápido) |
| `ParsingEngine.kt` / `ScreenRouter.kt` | interpreta as ofertas e recebidos |
| `DatabaseHelper.kt` | histórico de corridas, despesas e abastecimentos |
| `OfflineMapPackManager.kt` | download e verificação dos pacotes de mapa |
| `map/` | motor de rota, catálogo de regiões, estilos e navegação |
| `LeitorDeTela.kt` | ponto único de leitura de imagem (hoje sem motor) |

## Decisões desta fase

1. **Sem Firebase e sem Play Services**: nada de login, Firestore, Billing ou ML Kit.
2. **Sem login**: o app abre direto; não há conta, plano ou sincronização.
3. **Sem banco remoto**: preferências e histórico ficam só no aparelho.
4. **Mapa colaborativo desligado**: `SinalIndisponivelRepository` devolve lista vazia e avisa
   que o recurso chega na próxima fase — nenhum pino fictício.
5. **OCR desligado**: `LeitorDeTela.ler()` devolve `null` até a escolha de um novo motor.
   A leitura pela árvore de acessibilidade continua funcionando.
6. **Premium**: `Premium.ativo()` devolve `true` (tudo liberado) enquanto não há cobrança.

## Pendências conhecidas

- Escolher e integrar um motor de OCR (o anterior foi removido junto com o Google ML Kit).
- Publicar os pacotes de mapa offline no repositório novo (`map-packs-v1`).
- Política de privacidade e página de exclusão de dados para a Play.
- Revisar os textos de Apoiar/PIX antes de publicar.
