# DriFly Condutor — mapa do projeto

Documento curto do estado real do código nesta fase. O que ainda não existe está marcado
como pendente em vez de descrito como pronto.

## Stack

- Android nativo em Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1
- `compileSdk`/`targetSdk` 36, `minSdk` 29
- Material 3 + AppCompat (sem conta Google)
- ML Kit Text Recognition embutido (OCR offline)
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
| `LeitorDeTela.kt` | ponto único de leitura de imagem (ML Kit Text Recognition) |

## Decisões desta fase

1. **Sem Firebase e sem conta Google**: nada de login, Firestore ou Billing. A única peça
   do Google é o ML Kit Text Recognition embutido, usado só para o OCR local.
2. **Sem login**: o app abre direto; não há conta, plano ou sincronização.
3. **Sem banco remoto**: preferências e histórico ficam só no aparelho.
4. **Mapa colaborativo desligado**: `SinalIndisponivelRepository` devolve lista vazia e avisa
   que o recurso chega na próxima fase — nenhum pino fictício.
5. **OCR ativo**: `LeitorDeTela.ler()` usa ML Kit Text Recognition (modelo embutido no APK,
   sem internet) e devolve as linhas com a posição na captura. A árvore de acessibilidade
   continua sendo a primeira fonte.
6. **Premium**: `Premium.ativo()` devolve `true` (tudo liberado) enquanto não há cobrança.

## Pendências conhecidas

- Servidor dos pinos do mapa colaborativo (a camada já existe, falta a API).
- Regerar/ampliar os pacotes offline além de Joinville (SC) e Pará (PA).
- Política de privacidade e página de exclusão de dados para a Play.
- Revisar os textos de Apoiar/PIX antes de publicar.
