# ws://許可の実JAR比較検証

公開Relayには接続せず、127.0.0.1だけにbindした一時WebSocketサーバーを使用しました。実際の配布JAR内のRelayTransportを独立したClassLoaderで呼び出し、本番用コンストラクターを使用しています。修正版のテスト専用loopback許可は使っていません。Minecraftや実アカウントは起動せず、実token・実座標は使用しません。

| 対象 | TCP接続 | ONLINE | 架空token/座標を平文で受信 |
| --- | --- | --- | --- |
| 0.4.1（比較対象の旧版） | 成功 | 成功 | 受信した |
| 0.6.0（署名済み配布JAR） | URL検査で拒否 | ならない | 受信なし |
| 0.6.1（今回の署名済み配布JAR） | URL検査で拒否 | ならない | 受信なし |
| 0.7.0（任意Relay設定対応の署名済みJAR） | URL検査で拒否 | ならない | 受信なし |

0.4.1 SHA-256：`89dc69c259d3f709aba30b166e9db5abbef4f941282c39f7a11b0049980cbf10`

0.6.0 SHA-256：`315651e66aeeb03ee185be3b8e40f71cdd90f5a1597b6168b8ce84944492867c`

0.6.1のSHA-256は同梱のJAR.sha256を参照してください。

0.7.0 SHA-256：`6632dab3fa3237730c04773c8f3297654ca637b7ee1fbe9b449ffc63559df3b2`

旧MODがws://で接続する挙動を再現しました。これは公開NPMが当時平文のUpgradeを受け付けたことや、実際の盗聴被害の証明ではありません。NPMが301等で拒否していても、旧MODそのものが別の平文Relayへ接続できるという欠陥は残ります。修正版は本番経路でws://を接続前に拒否します。

## 再実行

ソース同梱の `checks/verify-ws.ps1` をJDK25とGson2.14.0で実行します。旧JARはこの文書に記載したSHA-256一致を必須とし、変更しません。テスト用ファイルは一時フォルダーだけに作ります。

```powershell
.\checks\verify-ws.ps1 -OldJar '旧0.4.1.jarのパス' -NewJar '新0.6.1.jarのパス' -GsonJar 'gson-2.14.0.jarのパス' -JavaHome 'JDK25のフォルダー'
```

旧版は `tcp=true online=true plaintextSyntheticToken=true plaintextSyntheticCoordinate=true`、新版はすべてfalseになります。新しい版は加えてERROR状態を確認します。例外やHTTP本文に実設定を埋め込まず、テスト文字列そのものも出力しません。プローブのソースは `checks/WsProbe.java` に同梱しています。

## 0.8.0の再検証

署名済み実JARでもTCP接続なし・ONLINEなし・平文データ受信なしを確認しました。SHA-256：`62d4eef9a31a0116f34c85075a15fa3519c4ee8eb132d15881fba687da13df45`。公開Relayへの接続は行っていません。
