# Regras de ofuscação do DriFly Condutor.
# A minificação está desligada no release por enquanto; este arquivo existe
# para o build de publicação não falhar quando ela for ligada.

# MapLibre
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
