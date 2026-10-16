# Long Range Players — Fabric 26.2

遠距離のプレイヤーを別のRelay経由で表示するクライアントMODと、自分の環境で運用できるNode.js Relayです。

開発版0.8.0（通信プロトコル4）。変更履歴は[CHANGELOG.md](CHANGELOG.md)。MODとRelayの両方の更新が必要です。

## 通信の仕組みと信頼の範囲

```text
Minecraftクライアント（本MOD）
  ├─ HTTPS → Mojang公式セッションサービス（アカウント本人確認）
  └─ WSS   → TLS対応リバースプロキシ
               └─ HTTP/WebSocket → Relay → 同じ部屋の同じグループのクライアント
```

1. 利用者が送信先と説明を確認し、明示的に同意します。未同意では本MODからRelayや本人確認先へ接続しません。
2. Relayはコード形式とUUID拒否一覧を確認し、使い捨てチャレンジを返します。MODとRelayがMojang公式サービスを使ってアカウントの本人性を確認します。ログイントークンは公式HTTPSへだけ送信し、Relayには渡しません。
3. グループコード・Minecraft接続先・ワールド識別値で部屋を分け、通常約10回/秒の表示情報を中継します。
4. クライアントは通常のプレイヤーが存在する間は第二の体を表示せず、遠距離では受信した状態から表示します。JourneyMap/ReplayMod連携は該当MODがある場合だけ使用します。

本人確認が証明するのはMinecraftアカウントの所有です。Minecraftサーバーへの現在の在席・権限・申告位置は証明しません。ワールド識別値は秘密鍵ではありません。同じ通知値を返す別バックエンドは区別できません。

WSSの暗号化はリバースプロキシまでです。このComposeのプロキシ→Relay区間は平文なので、信頼できる閉域網または暗号化トンネル内に限定します。エンドツーエンド暗号化ではなく、Relay管理者は中継中の情報を読めます。

## 外部へ送信する情報

| 送信先 | 情報 | 目的・保存 |
| --- | --- | --- |
| 設定したRelay | グループコード | 表示相手を分離。事前登録不要、ログには保存しない |
| 設定したRelay | MCID・UUID、Minecraft接続先アドレスとワールド識別値のハッシュ、ディメンション | 識別・部屋の分離。ハッシュは匿名性や秘密性を保証しない |
| 設定したRelay | ゲーム内X/Y/Z座標、視線・頭・身体の向き、姿勢、装備アイテムID、腕振り・アイテム使用等、スキン表示部位 | 遠距離表示。最後の姿勢は通常、更新停止から約5秒で破棄。座標・装備はアクセスログに保存しない |
| 設定したRelay | TCP接続元IP（プロキシ経由では通常プロキシのIP）、接続/切断時刻、接続時間、本人確認チャレンジ、同意説明のハッシュ | 接続管理・認証。アクセス履歴のMCID・UUID・IP等は設定日数（初期7日）で削除。チャレンジ・トークンはログに残さない |
| Mojang公式HTTPS | Minecraftログイントークン、プロフィールID、使い捨てセッション証明用ハッシュ。接続元IPも公式サービスに伝わる | アカウント本人確認。本MODはログイントークンを設定・ログ・Relayへ保存/転送しない |

透明化・スペクテイター中は、位置・姿勢・装備等を生成・送信する処理を呼ばず、理由や座標のない表示取り下げ通知だけを送ります。接続管理は続きます。サーバー独自vanishを検出できない場合は手動で無効化してください。現実のGPS位置は取得しません。

同じ部屋の受信者は表示情報を閲覧・録画できます。送信停止や同意撤回で相手の録画を回収することはできません。プロキシ側のログや管理者が作ったバックアップはRelayの自動削除対象外です。

## Relay

`.env.example`を`.env`へコピーし、待受IP・ポート・ログ保存日数・拒否UUID一覧を設定します。`docker compose up -d`でビルド・初期化・起動します。コードの登録やUUID事前許可は不要です。[RELAY-UPDATE.md](RELAY-UPDATE.md)を参照してください。

グループコードは8〜64文字の半角英数字（英字と数字の両方必須、大文字小文字を区別）。Mod Menuまたは`/lrp code`で入力し、仲間と共有します。ランダムな16文字の生成・クリップボードコピーも可能です。保存はRelay URLとMinecraft接続先ごと、空欄で削除・通信停止です。コード変更は共有相手の変更となるため再同意が必要です。コードは接続許可やE2E暗号化の鍵ではなく、第三者も独自グループでRelayを利用できます。

コードは利用者PCの設定へ平文保存され、設定ファイルを所有者権限に制限します。Relayへも送信しますが、ログや他ユーザー向けパケットに載せません。短い・推測可能なコードは避けてください。

## MOD

Minecraft 26.2、Java25、Fabric Loader 0.19.3以上、Fabric API 0.155.2+26.2以上、Cloth Config 26.2.155系列が必要です。Mod Menu、JourneyMap、ReplayMod、Emotecraftは任意です。

初期状態では送信しません。0.8.0では、初期接続先は`wss://lrp.ebmy.party/ws`、運営者は`yukugura`、問い合わせ先は配布元のDiscordスレッドです。Mod Menuまたは設定JSONから変更可能で、WSSのみ許可します。

```json
{
  "enabled": false,
  "relayUrl": "wss://lrp.ebmy.party/ws",
  "relayOperator": "yukugura",
  "relayContact": "配布元のDiscordスレッド",
  "relayRetentionDays": 7,
  "consents": {},
  "privacyDecision": "",
  "debug": false,
  "minDistanceChunks": 2,
  "journeyMap": true
}
```

初回起動時に説明を表示します。初期Relay情報は設定済みです。別Relayでは運営者の案内に合わせて変更し、Mod Menuから有効化し、説明に同意してください。対象Minecraftサーバー・ワールドでも確認します。送信先・説明・アカウントが変わると以前の同意は使えません。ws://、URL内認証情報、クエリは拒否します。

`/lrp privacy`で説明、`/lrp revoke`で撤回、`/lrp list`で接続者、`/lrp world`でワールド識別値を確認できます。debugを有効にすると接続理由をHUDへ表示します。

スキン・姿勢・装備・望遠鏡・JourneyMapのスニーク非表示・Replay録画と後参加スキン対応を維持します。透明化・スペクテイター中は位置等を送信せず表示を取り下げます。

## 配布と制約

BEFORE-DOWNLOAD.mdとDISCORD-TERMS.mdは運営者が自分の情報を記入するテンプレートです。ダウンロードリンクより前に説明を掲載してください。空欄のまま運用情報として公開しないでください。

Mojang本人確認とグループコードが必要です。UUID事前許可は不要で、管理者は拒否UUIDを指定できます。コードを知る第三者も参加できるため共有相手を選んでください。ただしMinecraftへの現在の在席・申告位置は証明しません。Relay管理者は中継情報を読め、E2E暗号化ではありません。同一プロキシの別バックエンドが同じワールド通知値を返す場合は区別できません。

現在の制約は上記の説明、署名確認は[VERIFY.md](VERIFY.md)、検証結果は[TESTING.md](TESTING.md)を参照してください。

## ソースの確認箇所

- `mod/src/client/java/dev/codex/longrange/client/ConsentScreen.java`・`ConsentRules.java`：同意と拒否。
- `PrivacyPolicy.java`・`ClientConfig.java`：接続先と説明、変更時の同意無効化。
- `PresenceConnection.java`・`PoseDisclosure.java`：送信内容と透明化時の非送信。
- `RelayTransport.java`・`RelayDecoder.java`：WSS制限、キュー・入力検証・再接続。
- `MojangIdentity.java`・`SessionProof.java`：公式アカウント確認。
- `relay/secure-relay.mjs`・`access-policy.mjs`：参加許可、本人確認、部屋分離と制限。
- `relay/audit-log.mjs`・`server.mjs`：アクセスログとTCP接続元の記録。
- `mod/src/check/java`・`relay/test`：ローカル回帰テスト。

ソース公開先：[ebmy-party/long-range-players](https://github.com/ebmy-party/long-range-players)。公開時の確認項目は[PUBLISHING.md](PUBLISHING.md)に記載しています。

## 検証

`gradle -p mod build`と`node --test relay/test/*.test.mjs`でローカル検証できます。ビルド・自動テスト成功をゲーム実機や公開環境の動作確認済みとは扱いません。公開サーバーへの転送・再起動はこの開発作業では行っていません。

## ライセンス

このリポジトリの[MIT License](LICENSE)を参照してください。
