# 配布JARの検証

配布用 `long-range-players-0.8.0+mc.26.2.jar` はRSA3072/SHA-256署名付きです。秘密鍵は配布物へ含めません。公開証明書はrelease-signing.cer、ファイルのSHA-256は隣接する.sha256です。

公開証明書のSHA-256フィンガープリント：

```text
12:69:85:55:59:B8:C2:F6:96:61:A4:E4:85:F3:3C:BD:A6:D2:4D:8F:96:8C:12:D0:41:7A:FC:12:8A:29:87:B5
```

配布者はこのフィンガープリントとJARのSHA-256を信頼できる配布元Discordスレッドに掲載してください。攻撃者がJARと同梱証明書を両方置き換えた場合、同梱証明書だけでは配布元を認証できません。自己署名証明書は配布者の実在身元を認証局が確認したものではありません。

JDK25のツールで確認する例です。

```sh
keytool -printcert -file release-signing.cer
jarsigner -verify -verbose -certs long-range-players-0.8.0+mc.26.2.jar
```

フィンガープリントを配布元の値と照合してから、専用のローカルtruststoreへその公開証明書を登録すればstrict検証もできます。

```sh
keytool -importcert -alias lrp-release -file release-signing.cer -keystore lrp-public-trust.p12 -storetype PKCS12
jarsigner -verify -strict -keystore lrp-public-trust.p12 long-range-players-0.8.0+mc.26.2.jar
```

truststoreのパスワードは手元で決めて入力します。署名はJAR内のファイル内容を検証します。ZIPのPOSIX権限等は署名対象外という警告が出る場合があります。本JARにsymlinkやパス逸脱がないことは別途確認しました。公開タイムスタンプサーバーには接続していません。

Fabricが署名の信頼性を自動的に判断してくれるという保証ではありません。配布元の照合・署名検証は導入前に行ってください。ソースから作り直したJARは配布署名と一致しません。公開鍵で秘密鍵を再現することはできません。
