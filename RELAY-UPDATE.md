# Relayの起動と更新

Docker EngineとDocker Compose v2を用意し、配布ZIPを任意のフォルダーへ展開します。ホスト側のNode.jsやセットアップスクリプトは不要です。

## .env

`.env.example`を`.env`へコピーします。

```dotenv
RELAY_BIND_IP=0.0.0.0
RELAY_PORT=8080
RELAY_RETENTION_DAYS=7
RELAY_DENIED_UUIDS=
```

| 設定 | 内容 |
| --- | --- |
| RELAY_BIND_IP | ホストの待受IP。0.0.0.0は全IPv4インターフェースで待受するため、別マシンのNPMからも接続可能。127.0.0.1/localhostでは同じホスト内だけとなる |
| RELAY_PORT | ホスト側ポート。初期8080 |
| RELAY_RETENTION_DAYS | アクセスログ保存期間。初期7日、1〜30日。設定するのはRelay側だけ |
| RELAY_DENIED_UUIDS | 接続を拒否するMinecraft UUID。カンマ区切り、空欄なら拒否指定なし。ハイフン付き・なしに対応 |

グループコードの事前登録とUUID事前許可は不要です。拒否したUUIDはアカウント本人確認済みでも利用できません。

## 起動

```sh
docker compose up -d
```

環境に応じsudoを付けてください。Composeがビルド・保存先初期化・起動を行います。storage-initのExited (0)は正常、relayがUp/healthyで起動完了です。

```sh
docker compose ps -a
docker compose logs --tail=30 relay
```

版0.9.0／protocol=5が表示されます。NPMからはRelayマシンのIPと上記ポートへHTTP/WebSocketで転送します。MODからNPMまではWSS、NPMからRelayまでは平文です。公開ドメイン・TLS証明書・NPMは別途設定してください。通信できる範囲はホストのファイアウォール設定にも依存します。

## 更新

既存.envとログボリュームを維持し、新しいrelayフォルダーとdocker-compose.ymlを配置して、`docker compose up -d`を実行します。

以前の.envが`RELAY_BIND_IP=127.0.0.1`の場合、別マシンからの接続には`0.0.0.0`へ変更してください。既存.envは自動上書きしません。

UUID拒否一覧や保存日数など.envの変更も`docker compose up -d`で反映します。単なるrestartでは環境変数は更新されません。コンテナ再作成時に既存接続は切れ、拒否UUIDは再接続できなくなります。

MODとRelayの両方を0.9.0へ更新してください。公開配布のMODは既定ドメイン専用です。別ドメインのRelayを利用する場合はクライアントの接続先定数を変更したビルドが必要です。
