#!/bin/sh
set -e

# El wallet de Oracle llega en ORACLE_WALLET_BASE64 (base64 evita que el panel
# de Dokploy se coma los binarios). Se acepta el .zip tal cual lo descarga
# Oracle, o un tar.gz del directorio ya descomprimido.
if [ -n "$ORACLE_WALLET_BASE64" ]; then
  mkdir -p "$ORACLE_WALLET_DIR"
  echo "$ORACLE_WALLET_BASE64" | tr -d ' \n\r' | base64 -d > /tmp/wallet.bin
  if [ "$(head -c 2 /tmp/wallet.bin)" = "PK" ]; then
    unzip -oq /tmp/wallet.bin -d "$ORACLE_WALLET_DIR"
  else
    tar xzf /tmp/wallet.bin -C "$ORACLE_WALLET_DIR"
  fi
  rm -f /tmp/wallet.bin
  echo "Wallet extraído en $ORACLE_WALLET_DIR"
else
  echo "AVISO: ORACLE_WALLET_BASE64 vacío; se usa lo que haya en $ORACLE_WALLET_DIR"
fi

exec java $JAVA_OPTS -jar /app/app.jar
