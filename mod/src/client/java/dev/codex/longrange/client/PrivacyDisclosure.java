package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

final class PrivacyDisclosure {
    static void add(ConfigCategory category, ConfigEntryBuilder entries) {
        String[] lines = {
            "遠くのプレイヤーを表示するため、専用Relayへゲーム内情報を送信します。",
            "送信先：lrp.ebmy.party ／ 運営者：yukugura ／ 問い合わせ：配布元のDiscordスレッド",
            "送信する情報：プレイヤー名・UUID、サーバーとワールドの識別値、ディメンション、座標・向き・姿勢・装備・動作、グループコード。",
            "同じコード・サーバー・ワールドの利用者と表示を共有します。Relay運営者も中継情報を扱います。透明化・スペクテイター中は位置や装備を送りません。",
            "接続履歴（名前・UUID・接続元IP・時刻）はRelay側で保存します（標準7日）。座標はアクセスログに保存しません。",
            "本人確認のためMojang公式サービスへログイントークンを送ります。Relayへは渡しません。更新確認のためGitHubにも接続しますが、プレイヤー名や座標は送りません。",
            "同意はこのアカウントでの利用に適用されます。停止は設定の無効化または /lrp revoke、説明の再確認は /lrp privacy。"
        };
        for (String line : lines) category.addEntry(entries.startTextDescription(Component.literal(line)).build());
    }
}
