package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

final class PrivacyDisclosure {
    static void add(ConfigCategory category, ConfigEntryBuilder entries) {
        String[] lines = {
            "同意するまで本MODはRelayにも本人確認先にも接続しません。拒否してもMinecraftは遊べます。",
            "送信先：" + PrivacyPolicy.current().url(),
            "運営者：" + PrivacyPolicy.current().operator() + " / 問い合わせ：" + PrivacyPolicy.current().contact(),
            "目的：同じグループの利用者同士の遠距離表示、接続管理、不正利用対策。",
            "送信：MCID・UUID・接続先とワールドの識別値・ディメンション・ゲーム内座標・向き・姿勢・装備・グループコード。",
            "位置等は約10回/秒。現実のGPS位置は取得しません。",
            "透明化・スペクテイター中は位置・姿勢・装備を送りません。理由を含まない表示取り下げ通知で第二の体を消します。",
            "共有：Relay管理者と、同じコード・Minecraft接続先・ワールドの利用者。コードを知る第三者も参加できるため、共有相手を選んでください。",
            "記録：TCP接続元IP（プロキシ経由では通常プロキシのIP）・MCID・UUID・接続／切断時刻。本Relayの保存は最大" + PrivacyPolicy.current().retentionDays() + "日。座標はアクセスログへ保存しません。",
            "他利用者が受信した表示・録画は、送信を止めても相手側から回収できません。",
            "本人確認：Mojang公式HTTPSへMinecraftのログイントークンを送信します。Relayへトークンは渡しません。",
            "コードは端末の設定ファイルへ平文保存します。Relayへの接続許可や暗号化の鍵ではありません。Minecraftサーバーへの現在の参加そのものを証明する機能ではありません。",
            "停止：設定で無効化、または /lrp revoke。同意先・ワールド・アカウント・説明が変わると再確認します。"
        };
        for (String line : lines) category.addEntry(entries.startTextDescription(Component.literal(line)).build());
    }
}
