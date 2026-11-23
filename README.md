# Óptiker Medidas — Android nativo

Protótipo Android nativo para medição remota de:

- DNP OD / OE e DP total;
- altura de montagem multifocal quando o cliente está usando a armação exata;
- validação em tempo real semelhante a um fluxo bancário:
  1. cartão válido;
  2. rosto centralizado;
  3. cabeça reta;
  4. distância adequada;
  5. estabilidade;
  6. captura automática;
- revisão manual dos pontos sem perder a foto/calibração;
- 3 capturas para DNP e mediana;
- 2 capturas para altura;
- bloqueio quando a repetibilidade é maior que 1,0 mm;
- compartilhamento do resultado por WhatsApp ou outro app instalado.

## Tecnologia

- Kotlin
- CameraX 1.7.0
- ML Kit Face Detection bundled 16.1.7
- Android API mínima 23
- Sem WebView e sem Chrome

## Como abrir

1. Instale o Android Studio atual.
2. Abra a pasta `OptikerMedidasAndroid` como projeto Gradle.
3. Aguarde a sincronização do Gradle.
4. Conecte um Android por USB com depuração ativada ou use um emulador com câmera.
5. Clique em Run.

Para gerar APK:
`Build > Build App Bundle(s) / APK(s) > Build APK(s)`.

## Protocolo pensado para o cliente

- Segurar o celular na vertical.
- Usar câmera frontal.
- Cartão padrão ID-1 (85,60 x 53,98 mm), sem dados sensíveis visíveis.
- Cartão horizontal no guia acima dos olhos, encostado no plano da testa.
- Olhar reto.
- O app captura somente após cartão/rosto/posição/distância ficarem válidos e estáveis.
- A DNP final exige 3 capturas concordantes.
- Altura multifocal só é liberada com a armação exata já ajustada no rosto.

## Limitação importante

Este é um MVP técnico, não um instrumento metrológico certificado. Antes de usar medidas para produção real de multifocais, valide em uma amostra relevante de clientes comparando com pupilômetro/centrador e defina tolerâncias aprovadas pela Óptiker.


## Observação sobre o APK

Este pacote contém o **projeto Android nativo completo**. O APK precisa ser compilado pelo Android Studio/Android SDK porque este ambiente de geração não possui o SDK Android instalado.

Ao abrir o projeto pela primeira vez, o Android Studio pode solicitar o download do Gradle/SDK necessários. Aceite a sincronização e então use **Build > Build APK(s)**.
