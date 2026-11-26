# Assinatura do DriFly Condutor

A keystore e as senhas NÃO ficam no Git. Guarde uma cópia de segurança da keystore:
sem ela não é possível publicar atualização do app fora da Play.

## Arquivos locais (fora do Git)

- `app/drifly-condutor-release.jks` — alias `drifly_condutor` (RSA 4096, validade 10.000 dias)
- `signing.env` — senhas e caminho, lido pelo `app/build.gradle.kts`

O Gradle procura nesta ordem:

1. Variáveis de ambiente:
   - `DRIFLY_CONDUTOR_KEYSTORE_FILE`
   - `DRIFLY_CONDUTOR_KEYSTORE_PASSWORD`
   - `DRIFLY_CONDUTOR_KEY_ALIAS` (padrão `drifly_condutor`)
   - `DRIFLY_CONDUTOR_KEY_PASSWORD`
2. Arquivo `signing.env` na raiz (`DRIFLY_CONDUTOR_SIGNING_ENV_FILE` troca o caminho).

Sem nenhum dos dois o build de release falha de propósito — não assina com chave de debug.

## Impressão digital

```
SHA-1:   D2:FA:CC:B4:4F:8F:DF:18:73:AE:84:4C:A6:4C:D6:5B:A0:8C:61:02
SHA-256: 6F:18:A5:3D:E0:93:3A:68:CB:FA:6C:8F:87:8D:7B:A2:3D:65:49:F8:9C:F7:85:DF:2F:31:FF:83:F0:57:B4:D1
```

Para conferir no APK:

```bat
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

## Play Store

Ao publicar pela Play, ative o **Play App Signing**: a chave acima vira a upload key e o Google
assina a distribuição. O SHA-1 que os serviços externos conhecem passa a ser o da Play.
