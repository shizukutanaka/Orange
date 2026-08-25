# Orange 機能過不足監査 (Feature Audit)

作成: 2026-07-02 / 対象ブランチ: `claude/sleepy-hypatia-o9gwuv`

このドキュメントは、将来の別セッション・別モデル(Opus/Sonnet 等)が**前提コンテキストなしで読んで実装を引き継げる**ように書かれた機能監査リストである。各項目に該当ファイル・シンボル名・「なぜ問題か」・修正時の注意点を明記する。実装前に必ず「不変制約」を確認すること。

---

## 0. 不変制約(絶対に守ること)

- `INTERNET` 権限・`READ_CONTACTS` 権限は**追加禁止**。バックアップ同期なし(`android:allowBackup="false"`)。これが製品のプライバシー上の核心的約束。
- CI ゲート: `tools/check_no_network.sh`(ネットワークコード混入をブロック)、`tools/check_apk_size.sh`(APK ≤1MiB)。
- 開発・プッシュは **`claude/sleepy-hypatia-o9gwuv`** ブランチのみ。他ブランチへのプッシュは明示的な許可なしに行わない。
- テストは JVM 純粋ユニットテストのみ。**Robolectric は意図的に不使用**(`RoleMonitorTest.kt` のコメント参照)。Context 依存コードは純粋関数部分を分離してテストする(例: `FamilyCallback.normalizeAndValidate`、`ManualBlock.normalizeAndValidate`)。

## 0b. アーキテクチャ最小知識

- `CallDecision.kt` の `decide(ctx, state)` — 16層 first-match-wins の**純関数**(Android 型・Context・時計読み取りなし)。層順: 緊急番号 → Pause → 非通知 → 発信済み → ビジネス → スパムキャッシュ → ワン切り → 国内偽装 → **政府偽装警告(警察/税務署, `govAgencyImpersonationWarning()`)** → STIR/SHAKEN → 国際プレミアム → 高リスク国 → 外国一般 → DND → 高リスク時間帯 → 許可。
- `SilentBlockerService.kt` — Telecom framework とのアダプタ。`handleDecision()` が通知発火・キャッシュ書込を担当。
- 番号は生値を保存しない: `SpamCache.hash(prefs, number)`(per-install salt + SHA-256、salt は `SaltVault` が Keystore AES-GCM で暗号化)。
- `phoneVariants(number, callingCode)`(`CallDecision.kt`)— 国内形式⇔E.164 の相互展開。**番号照合・キャッシュ書込は必ず全バリアントに対して行う**のがコードベース全体の規約。

---

## 1. 不足している機能(deficiencies)— 優先度順

### 1-12. 国内プレミアムレート(0990 等)を止める層が無い【要製品判断】
- **発見経緯(2026-07)**: 一度も実行されていなかった `CallDecisionTest`(111テスト)を
  ランナーに取り込んだ際、`jp_premium_0990_caller_is_spoof` が失敗して露呈した。
- **事実**: Layer 11 は**国際**プレミアムレート(+800/+979/+882/+883、カリブ NANP)を静音するが、
  **国内**プレミアムレート(`0990` テレドーム、`0570` ナビダイヤル)に対応する層は存在しない
  (`CallDecision.kt` に `0990` の出現はゼロ)。桁数が正しい `0990XXXXXX` は
  `DomesticSpoofDetector` を正当に通過し、**Layer 16 まで落ちて RING** する。
- **旧テストの誤り**: `SILENCE + DOMESTIC_SPOOF` を期待していたが、これは
  「**構造的に存在しうるか**」を答える偽装検出器に「**この番号帯を歓迎するか**」という
  別種の問いを担わせるもので、機構違い。テストは実挙動(RING)を主張するよう修正済み。
- **論点(判断が必要)**:
  - **着信の危険度は低い**: 0990/0570 は**着信課金・着信専用**のサービス番号で、
    危険な方向は「ユーザーが 0990 に**かける**」(架空請求の典型手口)であって
    0990 から着信することではない。§1-2 で 0120/0570 について確認した性質と同じ。
  - よって**着信側で静音する価値は小さい**一方、**発信側で警告する**価値はありうる
    (`OutboundGuard` は現在ブロック済み番号への発信しか警告しない)。
  - ただし発信警告の追加は「ユーザーの明示的な行動を邪魔する」方向であり、
    §2-4 で警察/税務署への正当なかけ直しを妨げないよう**削除した**ばかりの領域。
    同じ轍を踏まないため、**要ユーザー判断**。

### 1-2. business_directory.csv 残り約73エントリの偽装リスク監査【✅ 2026-07 判断基準を確立・現状維持が正しいと確認】
- **場所**: `app/src/main/assets/business_directory.csv`(Layer 5 = 無条件サイレント信頼。警告なしで着信、STIR/SHAKEN 失敗すら無視)。
- **経緯**: 警察庁・国税庁がこのバンドルに誤って入っていた(= 偽装されても警告ゼロ)バグを発見・修正済み。警察庁 → `PoliceStationDirectory.kt`、国税庁 → `TaxAgencyDirectory.kt` に移し、「鳴らすが警告する」レーンへ変更した。
- **残課題**: 宅配業者(ヤマト・佐川 = 不在通知詐欺)、メガバンク(不正送金詐欺)等、偽装頻度の高い機関が今もサイレント信頼のまま。ただし警察/税務署と違い**正当な着信量が多い**ため、warn-but-ring 化はカスタマーサービス着信に摩擦を生む。機関ごとに判断が必要で、機械的に移せない。
- **回帰ガード**: `BusinessDirectoryBundleTest.shipped_csv_never_bundles_a_warn_directory_number` が `PoliceStationDirectory` / `TaxAgencyDirectory` の**全エントリ**を CSV と突き合わせる汎用テスト。新しい warn ディレクトリを作ったら、このテストの `warnDirectories` リストに追加すること。

#### 2026-07 調査 — 「機関ごとの勘」ではなく3条件の判断基準を導出

warn-but-ring レーンへ移すべきかは、以下の**3条件すべてを満たすか**で判定できる。
警察・税務署が移された理由も、宅配・銀行が移されていない理由も、これで一貫して説明がつく。

| # | 条件 | 根拠 |
|---|---|---|
| **A** | 文書化された手口が**その番号自体を偽装**する(単に「その組織を名乗る」ではない) | warn-but-ring は caller ID がバンドル番号に**一致した時だけ**発火する。ランダムな番号から「銀行員です」と名乗る手口には**一切効かない** |
| **B** | 攻撃ベクターが**音声通話**である | Orange は着信スクリーナーであり **SMS を一切見られない**(構造的制約) |
| **C** | その番号からの**正当な着信量が少ない** | warn の摩擦コストが有界であること |

**機関別の判定:**

- **警察 / 税務署 → warn-but-ring(実施済み・正しい)**: A ✓(警視庁新宿署の代表番号を偽装する手口が具体的に文書化)、B ✓、C ✓(本物の警察署から電話が来ることは稀)。
- **宅配業者(ヤマト/佐川/日本郵便)→ silent-trust のまま(据え置きが正しい)**: **B が決定的に不成立**。宅配便かたりの主要ベクターは**偽の不在通知 SMS + フィッシングリンク**であり、大手3社は「**SMS による不在通知は送っていない**」と明言している。つまり実際の攻撃は Orange が構造的に観測できない SMS 上で起きており、音声側の番号を warn 化しても**実害には一切届かない**。摩擦だけが増える。
- **銀行 → silent-trust のまま(現時点では移す根拠が弱い)**: **A が不成立寄り**。文書化されている手口は「犯人が銀行関係者を**かたって**電話 → 自動音声ガイダンス → フィッシングメール誘導」や「社会保険事務所・自治体職員を名乗る還付金詐欺 → ATM 誘導」であり、**銀行自身の代表番号を偽装する**ことが手口の中核だという記録は見当たらない。ランダムな番号からの「銀行員を名乗る電話」に対して、銀行番号の warn 化は効かない。

**副次的発見 — CSV エントリの多くは着信専用サービス番号**: 該当エントリは
`+81120…`(フリーダイヤル)/`+81570…`(ナビダイヤル)が中心。これらは本来**着信課金・着信専用**の
サービス番号で、企業が発信時にこの番号を発信者番号として通知するには
**`特定番号通知機能` の別途申込が必要**(既定動作ではない)。したがって
これらの番号からの正当な着信は「無い」わけではないが、通常の代表番号(03-xxxx 等)より**限定的**。
特に**宅配ドライバーは自分の携帯から掛けてくる**ため、`0570-200-000` のエントリは
**最も件数の多い正当な宅配着信を元々カバーしていない**。エントリの実効価値は
想定より小さい可能性がある(削除を推奨するものではないが、「消したら困る」根拠も薄い)。

**結論**: §1-2 は「機関ごとに個別判断」ではなく**上記 A/B/C で機械的に判定できる**。
現状の配置(警察・税務署のみ warn、宅配・銀行は silent)は**この基準に照らして正しい**。
将来 CSV に機関を追加する際は、A/B/C を満たすなら `PoliceStationDirectory` 型の
warn ディレクトリへ、満たさないなら CSV へ、と振り分ければよい。

### 1-3. ディレクトリ陳腐化への構造的対策【設計課題】
- **現状**: `ProtectionDataVersion.LAST_UPDATED`(= "2026-04"、**全ディレクトリ中の最古の確認日**を採用)を Settings フッターに表示するところまで実装済み。
- **未解決**: オフライン完結の約束ゆえ、更新手段はアプリ更新のみ。「最新の詐欺手口に追従」との緊張関係は構造的に残る。ネットワーク追加は不変制約違反なので**選択肢にない**。取りうる手はリリースサイクルの短縮か、表示の充実のみ。
- **注意**: ディレクトリ(`PoliceStationDirectory` / `TaxAgencyDirectory` / `business_directory.csv` / `ScamPrefixSeed` / `EmergencyWhitelist`)のデータを更新したら `LAST_UPDATED` も更新する。新しい日付ではなく**最古の検証日**を維持する規約(鮮度の誇張を防ぐため)。

### 1-4. 警告通知の実効性が未検証【UX 研究課題、コード修正ではない】
- 警察/税務署偽装・高リスク時間帯の警告は**通話応答後**に notification として届く。詐欺師と通話中の被害者に通知が届くという介入方法が行動を変えるかは未検証(オフライン設計ゆえテレメトリでの検証も不可能)。
- 改善候補: 通知の文言・タイミング・フルスクリーンインテント化などがあり得るが、いずれも設計判断が必要。
- **2026-07 精緻化 — 「攻撃性を上げるか」は二択ではなく段階だった**。実装を検証したところ、
  警告チャンネルは既に `IMPORTANCE_HIGH` + `CATEGORY_ALARM` + `enableVibration(true)` で、
  **音だけが `setSound(null, null)` で明示的に落とされていた**(理由コメントは無し)。
  段を分けると:
  1. **DND 突破**(`CATEGORY_ALARM`)= **既に ON**。DND 中こそ高齢者が出てしまう場面なので正しい。
  2. **振動** = **既に ON**。耳に当てた状態で届く唯一の物理シグナル。
  3. **音** = **意図的に OFF のままにすべき**(今回 KDoc に明文化)。この警告は warn-but-ring
     レーン、つまり**電話が鳴っている最中**に出る。着信音の上に通知音を重ねるのは
     ノイズ対ノイズで、緊急度は上がらず、「本物の警察は LINE に誘導しない」を**読む一瞬**を奪う。
  4. **フルスクリーンインテント** = **唯一の真の未決事項**。
  → よって W8 で人間が決めるのは「音を足すか」ではなく **4 のみ**。しかもこれは音量の問題ではなく
  **割り込みの問題**(高齢者を焦らせる/誤警告時の負荷 vs CHI 2025 の心理的圧力分断)。
- **副次的所見**: チャンネルの importance は**作成時にしか反映されない**。ユーザーが後から
  下げた場合コードは再主張しない — これは意図的(ユーザーの明示的な通知設定を上書きしない)。
- **2026-07 調査で得たエビデンス**: CHI 2025 論文 "It Warned Me Just at the Right Moment"(arXiv:2502.03964)は、**通話中のリアルタイム警告(画面表示+振動)が詐欺師の心理的圧力を分断し、早期介入ほど不可逆な送金判断を防ぐ**ことを 20名の被験者実験で示した。これは「フルスクリーンインテント化」候補を裏付ける初のエビデンス。ただし論文の手法は録音+外部LLM前提であり、Orange の `RECORD_AUDIO`/`INTERNET` 禁止制約とは相容れない — Orange が取れるのは「着信メタデータに基づく事前警告の提示強度を上げる」ところまで。**通知の攻撃性が上がる(高齢者を焦らせる/誤警告時の負荷)トレードオフがあるため、実装は引き続きユーザー判断**。関連する外部指標として、警視庁は都内ニセ警察詐欺の前年比38.8%減を防犯アプリ利用増と相関づけている(時事 2026-07-06) — 本カテゴリの介入が有効という状況証拠。
- **2026-07 追加調査 — 警告の「文言」に関する直接的エビデンス(実装反映済み)**: IEEE S&P 2025 / arXiv:2412.04014 *"(Blind) Users Really Do Heed Aural Telephone Scam Warnings"*(CISPA、盲導ユーザー36名 + 晴眼者36名を実際にコールドコールする自然主義的実験)は、警告を **baseline(なし) / short warning / contextual warning(詐欺の内容に先立って「これから何が起こるか」を具体的に説明)** の3条件で比較し、**contextual warning を聞いた晴眼者は全員が電話を切った**と報告している(指示どおり「1を押した」のは2名のみで、うち1名はスクリーンリーダーの操作性問題、もう1名は「詐欺師の時間を浪費させるため意図的に」)。すなわち**「怪しい」と伝えるだけの短い警告より、次に何が起きるかを予告する文脈的警告の方が行動変容に効く**。
  - これを受けて `police_warn_body` を short warning 型(「偽装の可能性。一度切って #9110」)から **contextual 型**(「本物の警察がLINEやビデオ通話に誘導したり、お金の話をすることはありません。あれば詐欺です」)へ4ロケール全て改訂。手口の具体は `PoliceStationDirectory.kt` の KDoc に記録済みの実手口(**折り返させて LINE/ビデオ通話へ誘導**)に基づく。`tax_warn_body` は元から contextual 型(「税務署が電話で支払いを求めることはありません」)だったため変更なし — **被害額の約7割を占めるニセ警察詐欺の側が弱い形式だった**のを揃えた形。
  - **これは通知の攻撃性(フルスクリーン化・音・振動)を一切上げない**ため、上記のトレードオフ判断を要さない。文言のみの変更で、論文が最も効果的と示した形式に寄せた。

### 1-5. 信頼済みインサイダー脅威モデルの欠落【脅威モデル課題】
- `SaltVault`(Keystore 暗号化)は端末外・遠隔の攻撃者向け。高齢者ユーザーの現実的脅威である「ロック解除済み端末を手にした家族・介護者」(financial elder abuse の主要ベクター)への防御・言及がコードにもドキュメントにもない。
- 対策はアプリ内 PIN 等になるが「設定画面を増やさない」という製品哲学と衝突する。**実装前にユーザー(プロダクトオーナー)の判断を仰ぐこと**。
- **⚠️ 2026-07 訂正 — ここで引用していた制約は事実でなかった**: `COMPETITIVE_ANALYSIS.md` の
  「設定画面を持たない」という主張を根拠にしていたが、実際には `SettingsActivity` が
  **4セクション**で存在する(`exported="false"` で3経路からのみ到達)。**存在しない制約が
  設計判断を止めていた**。ただし**結論は変わらない** — PIN を入れない理由は「設定を増やすから」
  ではなく、(a) ロック解除済み端末を物理的に持つ相手にアプリ内 PIN は防御にならない、
  (b) 介護者による設定代行(`docs/SETUP_GUIDE_FAMILY.md` の想定ユースケース)を壊す、
  (c) 守られている錯覚を与えるセキュリティシアターになる、という**より強い3点**で立っている。

#### 2026-07 追加調査 — この脅威は「見知らぬ人の詐欺」より一般的である

- **USC Keck School of Medicine の研究**(NCEA 相談ライン約2,000件の分析): 虐待の申告のうち
  **経済的虐待が最多で約55%**、加害者が特定できたケースでは **家族が最頻で約48%**。
  研究の結論は「電話・郵便・ネット詐欺が多数存在するにもかかわらず、**親族による経済的虐待の方が
  見知らぬ人による詐欺より多い可能性がある**」。
- NCEA の別集計では、経済的虐待の **53%** が家族(成人した子・配偶者)によるもの。
  **加害者が顔見知りの場合の平均被害額は約5万ドルで、見知らぬ人(約1.7万ドル)の約3倍**。
- 日本でも厚労省の高齢者虐待調査で**息子が加害者として最多**の傾向が継続して報告されている。
- **この製品にとっての含意**: Orange の全16層は「知らない番号=脅威」という前提で設計されている。
  しかし統計上より頻度が高く被害額も大きい加害者は、**`FamilyCallback` に登録され、Layer 4
  (outbound-known)で常に鳴る側にいる人物**でありうる。つまり Orange は、最も一般的な
  financial elder abuse のベクターに対して**構造的に無力なだけでなく、加害者を明示的に信頼リストへ
  昇格させる導線を持っている**。
- **ただし「だから PIN を付けるべき」とは直結しない**。
  - 端末を物理的に持つ家族に対しアプリ内 PIN は防御として弱い(端末ロック自体を共有していることが多い)。
  - 高齢ユーザーに PIN を課すと、**正当な介護者による設定支援**(この製品が想定する `SETUP_GUIDE_FAMILY`
    のユースケース)を同時に壊す。
  - 誤って「守られている」と感じさせる**セキュリティシアター**になるリスクが高い。
- **したがって推奨は機能追加ではなく、脅威モデルの明示**: `THREAT_MODEL.md` に
  「信頼済みインサイダーは対象外」と**明記**し、`HONESTY_ADDENDUM.md` の「捕まえないもの」に
  この最頻ベクターを追加する。**Orange は電話network上の見知らぬ攻撃者に対する製品であって、
  同居家族による経済的虐待の対策ではない** — これを書かないことは、
  製品の中核的主張である「誠実さ」に反する。**この文書化なら哲学と衝突しない。**

### 1-6. テストスイートがCIで一度も実行されていなかった【プロセス課題 + 実バグ5件発覚】
- **発覚(2026-07)**: `.github/workflows/` は `.gitignore` で除外されており、静的ゲート `check_comprehensive.sh` は `@Test` アノテーションを**数えるだけ**でテストを**実行しない**。つまり JVM ユニットテストは自動実行された実績が皆無だった。当時の SESSION_SUMMARY.md 自身も「static CI never caught them (only counts @Test annotations)」と書いていた(同ファイルは 2026-07 に削除。全見出し数値が陳腐化していたため — 経緯は下記「解消済み」)。
- **✅ 2026-07 解決 — 除外は「判断」ではなく事故だった**: 当初この項目は「誰かが意図的に CI を除外した」と解釈していたが、`git log -S` で追跡した結果**誤りと判明**。除外行はリポジトリの**初回コミット**(`2e3f7c7`、138ファイル)から存在し、しかも `# Python (bytecode cache)` という**無関係な見出しの下**に、**理由コメント無し**で置かれていた(同ファイルの他エントリは「Keystore must never be committed」等すべて理由付き)。決定的なのは、同じ `.gitignore` が Gradle wrapper jar について「downloaded at **CI**/build time … **CI bootstrap step fetches it**」と書いており、**CI の存在を前提にしながら CI の commit を禁じる自己矛盾**を抱えていたこと。除外行を削除し、ワークフローを作成した。**ただし現在は `docs/ci/ci.yml` に待機中** — このセッションの GitHub App トークンに `workflows` 権限が無く、`.github/workflows/*` に触る push は GitHub 側で拒否されるため。人間が `git mv docs/ci/ci.yml .github/workflows/ci.yml` して push すれば即座に有効(手順は `RELEASING.md` 冒頭)。ワークフロー自体は暫定ではなく、static ジョブの全ステップをローカルで実行して通過を確認済み。
- **CI の中身**(サプライチェーン面を最小化するため **first-party action のみ**使用): `static` ジョブ(privacy guard / oracle 31件 / comprehensive / **ロケール4言語のキー集合一致** / XML・JSON 妥当性)、`unit-tests` ジョブ(`run-pure-tests.sh` = SDK 不要の285テスト)、`android-build` ジョブ(wrapper bootstrap → `testReleaseUnitTest` → `lintRelease` → `assembleRelease` → APK サイズ予算 → APK artifact)。
- **なぜ hooks では不十分か**: `.githooks/` は `git config core.hooksPath .githooks` を実行した人にしか効かない**オプトイン**で、fresh clone は無防備。CI は**迂回できない**版。
- **今セッションで初めて実行**: Gradle ディストリビューション同梱の `kotlin-compiler-embeddable` + `junit-4.13.2` を使い、Android SDK 無しで `tools/run-pure-tests.sh` を追加。当初は Android 非依存サブセット(199 tests)のみだったが、**最小の Android 型スタブ(SharedPreferences を Java で書き platform type を再現 / Base64 / keystore 型 / `edit` 拡張。ロジックなし、`/tmp` 限定・非コミット)を追加してストア層(SpamCache/OutboundGuard/WangiriTracker/RepeatCaller/RateLimiter/AllowSuffix/BlockHistory)も対象に拡大**。現在 **17 main sources + 17 test files, 285 tests** を実行。**NotificationManager/NotificationCompat/Context/Activity/Service/Widget 依存(WarningNotifier, ManualBlock, FamilyCallback, TrustNotifier, BusinessDirectoryBundle, UI 各種)は依然として対象外** — 通常の `./gradlew testReleaseUnitTest` が必要。
- **合計5件の失敗を検出**(全て「独立した FakePrefs 実装での直接プローブ」でシム副作用でないことを裏取り済み):
  - 1件: 今セッションの意図的変更(高リスク時間帯警告が Pause 中も残る)による陳腐化テスト → 修正済み。
  - 3件: **非現実的なタイムスタンプに起因する脆いテスト**(本番コードは正しいことをプローブで確認)→ 修正済み(下記「解消済み」)。
  - 2件: **DomesticSpoofDetector の設計判断**(§1-7)→ 意図的に失敗のまま残置。
- **現在の期待値**: `bash tools/run-pure-tests.sh` → **419 run / 0 failures**(§1-7 の意図的失敗2件は E.164 決着後にテスト側を修正して green 化)。スクリプトの終了コード契約は「ALLOWED リスト外の失敗で exit 1」で、**ALLOWED は現在空** = いかなる失敗も回帰。
- **`.githooks/pre-push` に配線済み**: `./gradlew` + wrapper jar があれば従来どおり `testReleaseUnitTest`(全件)。無ければ(fresh clone / SDK 無しサンドボックス等)`run-pure-tests.sh` を実行して push をゲートする。これにより「テストが一度も走らないまま push される」状態を、SDK が無い環境でも部分的に解消。**CI(`.github/workflows/` 復活時)にも同じランナーを組み込むこと**(§5 対応推奨順)。

### 1-7. DomesticSpoofDetector が先頭ゼロ無し/短縮番号を棄権する【✅ 2026-07 解決済み】

> **解決**: ITU-T E.164 の分析(下記)で「棄権 = 契約どおり」と確定したため、**テスト側を修正**した。
> `missing_leading_zero_is_spoof` → `bare_nsn_without_leading_zero_is_not_impossible`(false を期待)、
> `short_code_110_is_flagged_as_impossible_by_detector` → `short_code_110_is_abstained_on_not_flagged`
> (false を期待。旧コメント「The detector correctly flags it」は事実と異なった)。
> **分析の全文を両テストのコメントに移植**したので、シグナルとしての役割は失われていない —
> 失敗し続けるテストから、理由を語る green テストへ形を変えただけ。スイートは **285/0**、
> ランナーの ALLOWED は空になり「失敗は正常」という壊れ窓が消えた。以下は分析記録。
- **場所**: `DomesticSpoofDetector.toDomestic()`(`app/src/main/java/com/orange/apple/DomesticSpoofDetector.kt`)。
- **事象**: `toDomestic()` は入力が `"0"` 始まりでも `"+81"` 始まりでもない場合 `null` を返し、`isImpossibleJpNumber()` は `?: return false` で即座に**棄権**(= 偽装ではない)する。この結果、後続の `d.length < 10 → true`(短すぎる)や「先頭ゼロ欠落」判定は**到達不能**になっている。
- **実測(`tools/run-pure-tests.sh`)**: `isImpossibleJpNumber("110") = false`、`isImpossibleJpNumber("9012345678") = false`。しかし `DomesticSpoofDetectorTest` の2テスト(`short_code_110_is_flagged_as_impossible_by_detector`, `missing_leading_zero_is_spoof`)は `true` を期待しており、**実行すると失敗する**(コードとテストの矛盾)。
- **論点**:
  - `"110"`: ライブエンジンでは Layer 1(EmergencyWhitelist)が処理し、この検出器には決して到達しない(テストのコメント自身が認めている)。棄権(false)は実害なしだが、テストのコメント「The detector correctly flags it」は**事実と異なる**。
  - `"9012345678"`(先頭ゼロ欠落の携帯番号): ライブエンジンでは Layer 16 まで落ちて **RING** する。これを偽装として弾くべきかは numbering-plan の厳格性に関する**製品判断**。`toDomestic()` を先頭ゼロ無し番号も通すよう変更するとエンジン全体の挙動が変わり、慎重なテストが必要。
- **対応方針**: どちらも「検出器が非ドメスティック形式の入力を弾くべきか」という設計判断。**コードを一方的に変更したり、テストの assertion を黙って反転させたりしない**(後者は潜在的な gap を隠蔽する)。失敗テストが矛盾の可視シグナルとして機能する。**要ユーザー判断**。

#### 2026-07 調査 — ITU/E.164 の定義とコードベース規約から、答えはほぼ確定

**(1) 先頭の `0` は加入者番号の一部ではない。** ITU-T E.164 において JP の先頭 `0` は
**国内トランクプレフィックス**(「これは市外通話だ」と国内網に伝える信号)であり、
国際形式では除去される(`090-1234-5678` → `+81 90 1234 5678`)。つまり `9012345678` は
「壊れた番号」ではなく **national significant number (NSN) そのもの**で、ITU 的には正当な表現。

**(2) `isImpossibleJpNumber` の契約に照らすと `false` が正しい。** この関数が答えるのは
「この番号は MIC 番号計画上**構造的にありえない**か」。NSN はありえなくはない — 単に
プレフィックスが付いていないだけ。よって現在の `false`(棄権)は**契約どおり**。

**(3) 決定的な点: 仮に NSN を受け入れても、テストの期待は満たされない。** `toDomestic()` が
`9012345678` に `0` を補って `09012345678` と解釈したら、それは**有効な11桁携帯番号**なので
`isImpossibleJpNumber` は **`false` を返す**。つまり `missing_leading_zero_is_spoof` が
`true` を期待しているのは番号計画違反の検出ではなく「**配送形式が異常**」という
**別ルール**の要求。関数名・契約と噛み合っていない。

**(4) コードベース規約とも整合。** `phoneVariants()`(番号照合の single source of truth)も
domestic trunk 形式と E.164 形式の**2つしか展開しない** — bare NSN は自分自身しか返さない。
`toDomestic()` の棄権は**この境界と完全に一致**しており、片方だけ NSN を理解させると
「偽装検出器は解るが変換器は解らない」というスプリットブレインを生む。

**(5) `110` について**: `toDomestic("110")` が null なのは「`1` 始まりで `0` でも `+81` でもない」
から。Layer 1(`EmergencyWhitelist`)が先に処理するので**ライブでは到達しない**。テストの
コメント「The detector correctly flags it」は**事実と異なる**(flag していない)。

**残る実害(正直に記録)**: 攻撃者が bare NSN 形式で配送させれば `DOMESTIC_SPOOF` 層を
回避できる理屈は残る。ただし回避先は **Layer 16 = RING**(フェイルオープン)であり、
静音化ではない。またキャリアは domestic か E.164 でしか配送しないため、実現性は低い。

**推奨(要承認)**: **テスト側を実挙動に合わせる**のが正しい。ただし §1-8 の TTL や §2-4 と違い、
これは**私が意図的にシグナルとして残した failing test を消す**変更なので、独断では行わない。
消す場合は「なぜ `false` が契約上正しいか」を上記(1)-(4)ごとテストコメントに残し、
`110` のテストは名前と期待値を実挙動(`false` + Layer 1 が守る)に変更する。
**bare NSN を全面採用する**(`phoneVariants` も含めて対応)案は、実現性の低い形式のために
番号照合の中核を触るので**非推奨**。

### 1-8. SpamCache に TTL が無く恒久ロックアウトしうる【✅ 2026-07 解決済み】

> **解決**: 下記の調査を経て `isExpiringSilence()`(`CallDecision.kt`)を新設し、
> **状況依存の判断のみ 180 日で失効**するようにした。実装の詳細は §4「解消済み」を参照。
> 以下は判断に至るまでの分析記録として残す。

- `isCacheableSilence`(`CallDecision.kt:439-456`)は `CARRIER_VERIFICATION_FAILED` / `FOREIGN_GENERIC` / `DOMESTIC_SPOOF` で true を返す。よって **STIR/SHAKEN が壊れたキャリア経由の正当な発信者**や**正当な国際発信者**が、初回着信で全 `phoneVariants()` ぶんキャッシュされ、以後 Layer 6 の fast-path で恒久的に静音される。eviction は 10,000 件到達時の FIFO のみで **TTL なし**。
- 復旧経路はユーザーが気づいて History から Restore する導線だけ。`TrustNotifier` が `NotificationRateLimiter` で間引かれた/スワイプされた/通知が拒否されている場合、**気づく手段が実質ない**。
- **論点**: TTL や再評価の付与は「一度ブロックした番号は覚え続ける」という学習の永続性(製品の中核的価値)とのトレードオフ。**要ユーザー判断**。

#### 2026-07 追加調査 — 「永久保持」を支持しない3つの証拠

1. **電話番号は再割当される(最大の反証)**。米 FCC は年間約3,500万件(全番号の約10%)が再割当されるとし、キャリアは最短2日〜45日で番号を再供用する。日本でも総務省が未使用番号の再割当方針を示し(携帯は約3年目安)、実際の解約→再利用は**最短3ヶ月程度**との報告があり、「前の持ち主宛の電話が新しい所有者にかかる」問題は広く知られている。
   → **詐欺師が番号を捨て、善良な第三者に再割当された後も、Orange はその人を永久にブロックし続ける**。これは「学習の永続性」の利益ではなく、純粋な誤爆の永続化。ブロックされた側は自分がブロックされていることを知る術がなく、ユーザーも「かかってこない電話」に気づけない(**沈黙は観測できない**)。
2. **Orange の他ストアは全て TTL を持つ。SpamCache だけが例外**。
   `NotificationRateLimiter` 5分 / `RepeatCallerTracker` 60分 / `WangiriTracker` 6時間 / `OutboundGuard` 24時間 / `BlockHistoryStore` 30日 — **`SpamCache` のみ TTL なし**。同一設計者が他の5ストア全てで時間減衰を採用している事実は、TTL 不在が意図的方針ではなく**見落とし**である可能性を強く示唆する。
3. **`SpamCache` の KDoc に永続性の根拠が書かれていない**。KDoc はプライバシー設計(ハッシュ化)と上限(Carmack rule)は詳述するが、「なぜ期限を設けないか」には一言も触れていない。このコードベースは意図的判断を必ず KDoc に残す文化(例: `EmergencyWhitelist`、`isCacheableSilence` の網羅的 `when`)があるため、記述が無いこと自体が判断の不在を示す。

**さらに悪い相互作用**: `BlockHistoryStore` の TTL は **30日**。よって31日目以降は、**キャッシュは残っているのに、それを解除するための履歴 UI からエントリが消えている**。ユーザーの唯一の復旧導線が、ロックアウトより先に期限切れになる。

**推奨(ただし挙動変更のため要承認)**: `isCacheableSilence` が true を返す理由のうち、**番号の恒久的性質に基づくもの**(`DOMESTIC_SPOOF` = 番号計画違反、`PREMIUM_RATE_INTERNATIONAL` = 番号帯の性質)と、**その時点の状況に基づくもの**(`CARRIER_VERIFICATION_FAILED` = キャリア設定次第で変わる、`FOREIGN_GENERIC` = 単に発信履歴が無いだけ)を区別し、後者にのみ TTL(例: 90日〜再割当最短期間)を設けるのが最小の変更で最大の効果。`SPAM_CACHE` 自体(ユーザーが明示的にブロック)と `MANUAL_BLOCK` は永久のままでよい。

### 1-9. salt 回転で信頼集合が黙って失効する【⚠️ 2026-07 再評価: 想定より大幅に低リスク】
- Keystore キーが無効化されると(機種変更・キー invalidation)`SaltVault.decrypt` が null を返し(`:128-130`)、平文フォールバックは前回の暗号化成功時に削除済み(`:111`)なので**新しい salt が生成**される。
- 結果、`SpamCache.hash` に依存する**発信済み集合(outbound-known)**と `RepeatCallerTracker` のハッシュが全て不一致になり、長年信頼してきた正当な国際連絡先が再び FOREIGN_* 層に落ちる → さらに §1-8 により恒久キャッシュされる。**検知も通知もログもない**(家族番号は平文保存のため無事)。
- **論点**: 検知(salt 変更の記録)と再構築(信頼集合の移行)の設計が必要。**要ユーザー判断**。

#### 2026-07 再評価 — 前提の2つが誤りだった

当初の記述は「機種変更・キー invalidation」を主因として挙げていたが、一次情報とコードの照合で
**主要シナリオ2つが構造的に発生しない**ことが判明した:

1. **`KeyPermanentlyInvalidatedException` は該当しない**。この例外は Android の仕様上
   「**ユーザー認証を要求するよう構成された鍵**でのみ」発生し、引き金は画面ロックの無効化・
   ロック方式の変更・生体情報の追加/削除である。`SaltVault` の `KeyGenParameterSpec.Builder` は
   **`setUserAuthenticationRequired()` を呼んでいない**ため、これらの操作では鍵は失効しない。
   高齢ユーザーが指紋を再登録した程度で信頼集合が飛ぶ、という当初の懸念は**誤り**。
2. **機種変更シナリオは起こり得ない**。`data_extraction_rules.xml` は `<device-transfer>` で
   `sharedpref` を含む全ドメインを除外している(`allowBackup="false"` と二重)。よって新端末には
   **暗号文 salt も信頼集合も一切運ばれない**。「salt だけ新しくなって既存の信頼集合が孤児になる」
   という状態は構造的に発生せず、新端末は完全にまっさらから始まる。これは §1-9 が想定した
   ワーストケースそのものの否定である。

**残る実害シナリオ**(小さいが実在): Samsung 等で OS/セキュリティパッチ後に Keystore エントリが
破損する事例が報告されている。この場合 `decrypt()` が `catch (_: Throwable)` で null を返し、
平文フォールバックも(前回の暗号化成功時に削除済みなので)無く、`salt()` は新しい salt を生成する。
結果として発信済み集合・SpamCache・RepeatCallerTracker のハッシュが一斉に不一致になる。

**ただし影響の方向は「フェイルオープン」**: 信頼集合が消えると、既知の相手が
Layer 4(outbound-known)で拾われなくなり **FOREIGN_* 層に落ちて静音されうる** — これが実害。
一方 SpamCache も同時に失効するのでブロック済み番号は鳴るようになる(実害小)。
**そして §1-8 の TTL 導入により、この静音は最長180日で自動回復する**(状況依存の判断は失効するため)。
つまり §1-8 の副次効果として §1-9 の最悪ケースも**恒久ではなくなった**。

**再評価後の推奨**: 優先度を下げる。実装するなら「salt 生成時にカウンタを保存し、
2回目以降の生成 = 回転を検知したら §1-11 と同じ既存ダイジェスト経路で1回だけ知らせる」が
最小コスト。ただし発生頻度が低く、TTL で自動回復するため、**現時点では記録のみとし据え置く**判断も
十分合理的。

### 1-10. コールドスタート時に Keystore と prefs パースがホットパス【✅ 2026-07 解決済み】
- `EngineWarmup` は CSV と静的ディレクトリのみ warm し、`SaltVault.salt`/`SpamCache.hash` を warm しない。プロセス起動後の初回着信で **Keystore ラウンドトリップ(10–50ms、StrongBox ではさらに悪化)** を screening callback 内で払う。`SpamCache` 自身の KDoc(`:46-48`)が「これを毎回払うのは不要」と書いているのに、初回だけは実際に払っている。
- 加えて SharedPreferences の初回ロードは同期 XML パースで、`SpamCache.MAX_ENTRIES = 10,000` の64桁ハッシュ + `KEY_ORDER` の重複コピー + outbound 1,000件 = **1MB超**を screening スレッドで解析しうる。`SilentBlockerService.kt:24-25` の KDoc「disk I/O は prefs 1回読みのみ」はこの規模を過小評価している。
- **論点**: warmup への追加は容易だが、アプリ起動時に Keystore 初期化を持ち込む是非(起動コスト・キー無効化例外の扱い)は判断が要る。**要ユーザー判断**。

#### 2026-07 追加調査 — リスクの実測的な切り分け(3つのうち1つは杞憂、2つは実在)

1. **Keystore は「杞憂」寄り。StrongBox の巨大コストは該当しない**。
   公開ベンチマークでは StrongBox は TEE より桁違いに遅く、**Pixel 8 で 1MiB の対称暗号化に平均 15.43 秒、
   Pixel 3 では 63.43 秒**(TEE は Pixel 8 で 0.42 秒)。5秒デッドラインを単独で超える数字だが、
   **Orange には該当しない**: (a) `SaltVault` は `setIsStrongBoxBacked` を**要求していない**
   (KDoc が "TEE/StrongBox" に言及するのみで、`KeyGenParameterSpec.Builder` に指定なし)ため
   TEE 実装が使われる、(b) 暗号化対象は **16バイトの salt(hex 32文字)** で、ベンチマークの 1MiB とは
   5桁違う。TEE は「1MiB 以下ならネイティブ実行と無視できる差」とされる領域。
   → よって `SaltVault` KDoc の「10–50 ms」という自己申告は妥当。**この単体では緊急性は低い**。
2. **SharedPreferences の同期 XML パースは実在するリスク**。最悪ケースを実測算出すると:
   `KEY_SET`(10,000 × 64桁hex)+ `KEY_ORDER`(**同じハッシュ列をスペース区切りで完全に重複保持**)
   + outbound(1,000件)= **素で約 1.29 MB、XML タグのオーバーヘッド込みで約 1.7 MB**。
   SharedPreferences はファイル全体をメモリにロードする同期 API で、大きなファイルは UI/呼び出しスレッドを
   ブロックし ANR の要因になることが広く報告されている。これが screening コールバックの初回で発生する。
   → `SilentBlockerService` KDoc の「disk I/O は prefs 1回読みのみ」は嘘ではないが、**その1回の規模を
   過小評価**している。
3. **さらに悪いのは毎回の O(n) 走査**。`SpamCache.orderList()` は呼ばれる度に
   `KEY_ORDER` を `split(' ')` して全要素を `filter { it in known }` する(`known` は 10,000 要素の Set)。
   そして `add()` は**ブロック1件につき全 `phoneVariants()`(通常2〜3個)** 呼ばれる
   (`SilentBlockerService.kt:251`、`ManualBlock.kt:136`)。つまり **1ブロックあたり最大3万要素の走査**が
   ホットパスで走る。これはコールドスタート限定ではなく**毎回**。

**§1-8 との直結**: 上記の規模はすべて「キャッシュが 10,000 件上限まで単調増加する」ことが前提。
**TTL があればそもそもこの規模に到達しない**。§1-8 の TTL 導入は誤爆回復だけでなく、
**この性能問題の根本原因も同時に解消する**。逆に言えば、§1-10 を性能改善として個別に対処するより、
§1-8 を解決する方が費用対効果が高い。

**推奨(要承認)**: (a) §1-8 の TTL を優先(規模そのものを抑える)→ **2026-07 実装済み**、
(b) ~~`KEY_ORDER` の重複保持を見直す~~ → **2026-07 実測により却下(下記)**、
(c) `EngineWarmup` に `SpamCache.hash(prefs, "")` 相当の空打ちを1回入れて salt 復号と prefs ロードを
起動時に前倒し → **2026-07 実装済み**。`EngineWarmup` は元々「5秒デッドライン超過」対策として
CSV とディレクトリを warm する既存機構なので、同じ目的の残り2コスト(Keystore ラウンドトリップ・
prefs の同期 XML パース)をそこに足すのが自然だった。`hash("")` は空文字列の実ハッシュで、
prefs ロードと salt 復号の両方を強制し、何も書き込まず、呼び出し側が空番号を弾くため
既存エントリと衝突しない。Keystore 破損時に起動が止まらないよう `try/catch`(warmup は
最適化であって正しさの要件ではない)。

#### 2026-07 実測 — (b) `KEY_SET` の廃止は「冗長の削除」ではなく「インデックスの破壊」

`KEY_ORDER` が `KEY_SET` の全ハッシュを重複保持しているのは事実だが、**統合してはならない**。
10,000 エントリでの実測(最悪ケース = 末尾要素の探索、1000回平均):

| 方式 | contains() の所要時間 | 現行比 |
|---|---|---|
| **A) 現行**: `KEY_SET` の Set membership | **240 ns** | 1x |
| B) 統合案: `KEY_ORDER` を `split(' ')` して線形探索 | 1,404,577 ns (1.4 ms) | **5,852x 遅い** |
| C) 統合案の最適化: split せず部分文字列探索 | 5,077,974 ns (5.1 ms) | **21,158x 遅い** |

ストレージは 1,269 KB → 634 KB と半減するが、その代償が **screening ホットパスでの 1.4 ms**
(しかも `contains()` は全 `phoneVariants()` に対して呼ばれる)。**5秒デッドラインを持つ
`CallScreeningService` で払う値段ではない**。C 案が B より更に遅いのは、部分文字列探索が
64万文字を毎回走査するため(かつハッシュの部分一致という正当性バグも生む)。

**結論**: `KEY_SET` は冗長なコピーではなく**意図的な転置インデックス**。
「順序付き集合を1つの表現で持つ」のは一見きれいだが、SharedPreferences には
`LinkedHashSet` を O(1) 探索のまま永続化する手段がないため、
**空間 2倍と引き換えに時間 5,852倍を買っている**現行設計が正しい。この項目は**却下**とし、
将来「重複してるから消せる」と考える人のために `SpamCache` の KDoc に実測値ごと記録した。

### 1-11. ロール失効がユーザーに実質伝わらない【✅ 2026-07 解決済み】

> **解決**: `WeeklyDigest.onReceive` の早期 return を分岐に置き換え、ロール喪失時に
> **エピソードにつき1回だけ**「保護が停止しています」を既存チャンネル・既存 NOTIF_ID・
> 既存アラームで表示するようにした(タップで `OnboardingActivity` = 再付与フロー)。
> 実装詳細は §4「解消済み」を参照。以下は判断に至るまでの分析記録。
- `RoleMonitor` は意図的に無通知(KDoc `:37-39` 明記)。シグナルはウィジェットの `·` 一文字のみ(`OrangeWidget.kt:59`)で、**ウィジェット未設置なら永久に気づけない**(`RoleMonitor.kt:63` が `ids.isNotEmpty()` で gate)。
- 検知タイミングも BOOT_COMPLETED / MY_PACKAGE_REPLACED / ウィジェット30分更新のみ。**再起動もウィジェットも無いセッション中の失効は検知されない**。
- さらに唯一の定期接点である `WeeklyDigest` が `if (!RoleMonitor.isRoleHeld(ctx)) return`(`:36`)で**自らを抑止**するため、最も知らせるべき状態でこそ沈黙する。
- **論点**: 「設定画面を増やさない/通知を増やさない」哲学と真正面から衝突。**要ユーザー判断**。

#### 2026-07 追加調査 — プラットフォーム側に「気づく手段」が無いことの確認

- **Android はロール失効をアプリに通知しない**。`RoleManager.addOnRoleHoldersChangedListener` は
  `MANAGE_ROLE_HOLDERS`(signature レベル)を要する **system API** であり、サードパーティアプリからは
  利用できない。したがって Orange がロール失効を知る手段は**ポーリングのみ**で、これは実装の怠慢ではなく
  **プラットフォームの制約**。この事実は「なぜ検知が BOOT / パッケージ置換 / ウィジェット更新の3点しか
  無いのか」の説明になる(= 能動的通知が原理的に不可能だから)。
- **ただし Orange は既にポーリング機構を持っている**: `WeeklyDigest` の `AlarmManager`(週次→月次)。
  現状これは `if (!RoleMonitor.isRoleHeld(ctx)) return`(`WeeklyDigest.kt:36`)で**ロール喪失時に
  自分を無効化する**。つまり**唯一の定期的な起床機会を、最も知らせるべき状態でこそ捨てている**。
  ウィジェット未設置・再起動なしのユーザーにとって、これが最後の検知機会だった。
- **usable-security の一般則との一致**: サイレント障害は「認識されている安全性(perceived security)」と
  「実際のリスク(actual risk)」の乖離を生み、ユーザーは守られていると誤信したまま曝露され続ける
  (silent patch rollback 等で繰り返し報告されているパターン)。Orange の場合、
  **ロール喪失 = 全レイヤーが機能停止**なので乖離の幅は最大になる。しかも失効後は「電話が普通に鳴る」だけで、
  ユーザー体験上は**アプリが正常動作しているのと区別がつかない**(むしろ「ブロックされなくなった=平和になった」
  と誤解しうる)。
- **最小の改善案(要承認、ただし新しい通知チャンネルも設定画面も増やさない)**:
  `WeeklyDigest.onReceive` の早期 return を、**ロール喪失時は「保護が止まっています」という内容の
  ダイジェストを出す**分岐に置き換える。既存の `orange_digest` チャンネル・既存のアラームをそのまま使い、
  タップで `OnboardingActivity`(再付与フロー)へ送る。追加コストはゼロで、
  「通知を増やさない」哲学にも抵触しない(**通知の総数は変わらず、内容が状況に応じて変わるだけ**)。

---

## 2. 過剰な機能(excesses)— 統合・削減候補

### 2-2. 解除メカニズムの二重化(Allow vs Restore)
- **Allow**(`HistoryActivity` → `AllowSuffixStore`): 末尾4桁サフィックス一致。履歴がマスク番号(`****1234`)しか持たないための妥協。
- **Restore**(通知 → `RestoreReceiver`): 完全番号で `SpamCache.remove` + 発信済みセット追加 + `OutboundGuard.forget`。
- **問題**: ユーザーには同じ「ブロック取り消し」なのに、場所も保証も違う2つのボタン。ストレージ形式の実装都合が製品表面に漏れている。
- **注意**: 統一するには History にハッシュを保存する変更が必要で、これは意図的なプライバシー設計(`BlockHistoryStore` KDoc「display-only」)とのトレードオフ。**設計判断が必要、機械的修正不可**。

#### 2026-07 調査 — 判断材料の精密化(実装はしていない)

**(1) 文書バグを1件修正済み**: `BlockHistoryStore` の KDoc は
「restore action は**ハッシュを使って** spam cache を消す」と書いていたが、
`Entry` は `(maskedNumber, timestampMs, reason)` のみで**ハッシュを一切持たない**。
ハッシュを使うのは通知経由の Restore(Intent から完全番号を得る)であって、
このストアではない。読者に「History にハッシュがある」と誤解させる記述だったため、
2つの undo 経路が**なぜ非対等なのか**を明記する形に書き換えた。

**(2) プライバシー上の反対論は、当初の記述より弱い**。
「ハッシュを保存するのはプライバシー設計に反する」とされていたが、
**まさに同じ salted hash が `SpamCache` に既に保存されている** —
キャッシュ対象のブロック(`isCacheableSilence == true`)であれば、その番号のハッシュは
**すでにディスク上にある**。History に同じ値を書いても**新しい種類のデータではない**。
新規露出になるのは非キャッシュ理由(`DND_HONOR` / `REPEAT_CALLER`)のみ。

**(3) ただし「display-only」は「ハッシュを持たない」より強い主張**であり、そこは本物。
ハッシュは**メンバーシップ・オラクル**であり、候補番号を1つ与えれば照合できる。
つまり主張が「識別子を再構築**できない**」から
「候補リストが無ければ再構築できない」へ**弱まる**。連絡先リストを持つ
フォレンジック押収を脅威モデルに含めるなら、この差は実在する。

**(4) 現状維持のセキュリティ的コスト(逆側の実害)**: `AllowSuffixStore` は
**末尾4桁一致**なので、1回の Allow で **1万分の1の番号空間を無条件許可**する。
しかもこの判定は `SilentBlockerService` が**エンジンより前**に行うため、
**16層すべてを迂回**する。誤爆1件を救うために、末尾4桁が一致する詐欺番号も通る。

**(5) 安価な中間案 → 2026-07 撤回(前提が誤りだった)**: 当初「末尾4桁 + 桁数を保存すれば
プライバシー露出ゼロで衝突空間を絞れる」と記録したが、実装前の検証で**成立しない**と判明した。
`isAllowed` は着信の**生の配送形式1つ**で照合される(`SilentBlockerService.kt:144`)。同じ発信者でも
キャリアは domestic(`09012345678` = 11桁)と E.164(`+819012345678` = 12桁)のどちらでも
配送しうる — **それこそが `phoneVariants()` の存在理由**である。つまり:
  - 末尾4桁サフィックスが機能しているのは、まさに**両形式で末尾4桁が不変**だから。
  - **桁数はその不変性を持たない**。record 時 11桁・着信時 12桁で照合が外れ、
    **正当に Allow した番号が形式違いで鳴らない** = 誤ブロックの継続 = この製品の最悪の失敗方向。
  - 形式非依存に正規化するには callingCode の配管が `SilentBlockerService`(危険ファイル)→
    `BlockHistoryStore.Entry` → `HistoryActivity` → `AllowSuffixStore` の4ファイルに必要で、
    「安価」の枠を超える。
  - 得られるのは 1/10,000 誤許可(方向は fail-open = 鳴る)の数倍圧縮のみ。
    **失敗モードが fail-closed(鳴らない)の複雑化で、fail-open の小リスクを買う逆トレード**。
撤回し、教訓を明記する: **サフィックス設計の本質は「配送形式に対する不変量」であり、
第2シグナルを足すなら同じ不変性が必須**(桁数・先頭桁はいずれも形式依存で失格)。

**判断が要る点**: 中間案が消えたため、W6 は純粋に (3) の「display-only を弱めてよいか」だけになった。
(2) により**プライバシー上の増分は小さい**ことは示せたが、
**主張の強度を下げる**こと自体が製品の中核価値(誠実さ)に触れるため、
**独断では変更しない**。(5) の中間案なら哲学に触れずに実施可能。

### 2-4. 警察/税務署 warn-but-ring 番号へのかけ直しに発信警告が出る【✅ 2026-07 解決済み(警察/税務署) / 高リスク時間帯は据置】
- **経路**: `handleDecision()`(`SilentBlockerService.kt`)は警察/税務署の偽装警告(warn-but-ring)発火時にもその番号を `OutboundGuard.record()` する。警告後にユーザーが**本物の警察署の代表番号にかけ直す**(anti-scam 指導で推奨される安全行動そのもの)と、`showOutboundWarning` が発火する。
- **問題は2面**:
  1. **文言の事実誤り**: 通知 body は「この番号は直前にブロックした番号です」(`outbound_warn_body`)だが、warn-but-ring 番号は**ブロックしておらず着信させた**番号。15分以内なら緊急版(`outbound_warn_body_urgent`「送金や暗証番号の共有をしないで」)が本物の警察署への発信に対して出る。
  2. **推奨行動への摩擦**: スプーフィングされた着信の後、表示番号に自分からかけ直せば本物の警察に着信する(発信はスプーフィングできない)。この安全な確認行動を怖い通知で妨げる形になっている。
- **反論(現状維持の根拠)**: 高齢ユーザーにとって「かけ直しの前にひと呼吸」はそれ自体が保護であり、警告は発信をブロックしない(agency は保たれる)。#9110 は `EmergencyWhitelist` 対象外だが `handleOutgoing` の emergency チェックで記録されないため #9110 への相談経路は摩擦ゼロのまま。
- **2026-07 解決 — 選択肢(c)を警察/税務署に限定適用**: `handleDecision()` の警察/税務署分岐から `OutboundGuard.record()` を削除。技術的根拠により「判断」ではなく「バグ修正」と再分類できた:
  - **caller-ID スプーフィングは着信のみ**。表示された番号にかけ直すと**本物の所有者(=実在する機関)に繋がる**。この経路が発火するのはバンドル済みの実在機関番号に caller ID が一致した時=ほぼ確実にスプーフィング着信なので、かけ直し先は必ず本物。
  - FCC の公式ガイダンスは「**ハングアップして自分でかけ直す**」を推奨(RESEARCH_BASIS 参照)。Orange 自身の警察警告文も「一度切って #9110 へ」。**発信警告はこの推奨行動を妨害し、かつ「ブロックした」という事実誤認を伴う**二重の誤り。
  - **高リスク時間帯(`HIGH_RISK_HOUR_DOMESTIC`)は据え置き**。こちらは**未知の携帯番号**(スプーフィングされた実在機関ではない)で、かけ直すと詐欺師本人に繋がりうるため、発信警告は妥当。コメントで両者の違いを明記。
  - SILENCE 経路の記録(実際にブロックした番号)は当然維持 — こちらは「ブロックした」文言も正確。
  - テスト影響なし(`OutboundGuardTest` は store 単体の検証で、統合側の記録有無を前提にしていない)。

---

## 3. 未文書化(バグではないが注意)

- ~~`FamilyCallback.MAX_SLOTS = 3` — 根拠コメントなし~~ **対応済み**: 実際の選定理由は記録が残っておらず不明だが、「値を上げても記憶域はスパースな1-indexedキーなのでマイグレーション不要・安全」「変更を検討する場合は Settings UI の縦リストの使いやすさと Quick Settings タイルの one-tap 性とのトレードオフが論点」という判断材料を KDoc に明記した。値自体(3)は変更していない。
- 手動ブロック機能(`ManualBlock.kt`)は3段階の自己修正を経ている: ①構築 → ②`BlockHistoryStore.record(…, MANUAL_BLOCK, …)` を追加(可視性・undo経路の欠落修正)→ ③`HistoryActivity` の `blockCounts` 集計から `MANUAL_BLOCK` を除外(「N×」バッジの汚染修正)。この3点はセットで整合している。**どれか1つだけ変更すると再び壊れる**。
- `BlockReason` に enum 値を追加すると `isCacheableSilence`(`CallDecision.kt`)と `toDisplayString`(`HistoryActivity.kt`)の網羅的 `when` がコンパイルエラーで停止する設計。これは意図的なガード。

---

## 4. 解消済み(再発見の無駄を防ぐため列挙)

本ブランチのコミット(`333ddcb`〜)で以下は対応済み:

- 英語フォールバック `values/strings.xml` に日本語警告文が混入 → 英訳済み
- `AllowSuffixStore.revoke()` がデッドコード → Settings「許可した番号」管理 UI で配線済み
- **警察庁**が business_directory.csv でサイレント信頼 → `PoliceStationDirectory` へ移動(warn-but-ring)
- **国税庁**が同上 → `TaxAgencyDirectory` 新設 + Layer 9b 追加
- `TaxAgencyDirectory` の `EngineWarmup` ウォームアップ漏れ → 追加済み
- Pause 中に政府偽装警告が消える → `govAgencyImpersonationWarning()` 抽出で Pause 中も発火
- Settings 許可リストの Remove ボタンに TalkBack 説明なし → 行別 `contentDescription` 追加
- プエルトリコのオーバーレイ局番 939 が `CaribbeanPremiumNANP` に欠落 → 追加
- 保護データ確認日の非表示 → `ProtectionDataVersion` + Settings フッター表示
- 発信コールバック警告が「23時間前」と「90秒前」を同扱い → `OutboundGuard.flaggedAt()` + `ACTIVE_SCAM_WINDOW_MS`(15分)で緊急エスカレーション
- 手動ブロック機能自体の欠落 → `ManualBlock` + Settings UI + History 統合(3段修正済み)
- 信頼済み番号への手動ブロックが「成功」と誤表示 → `ManualBlock.classify()` を新設し `Result` 三値(`BLOCKED`/`INVALID`/`ALREADY_TRUSTED`)化。家族/ビジネス/発信済みの全バリアントと照合し、専用メッセージ(`settings_block_trusted`)を表示。`ManualBlockTest.kt` に `classify()` の単体テストを追加
- ラオス(+856)の高リスク国コード欠落、`callingCodeOf` の CA/TW/HK/SG/MY/NZ 欠落、夕方(18-20 JST)リスク時間帯欠落 → いずれも追加済み
- Pause の影響範囲が3経路(政府偽装警告/高リスク時間帯警告/発信警告)で非一貫 → 政府偽装警告と高リスク時間帯警告は同一の理由(アクティブな詐欺対策 ≠ Pause の通話量疲労対策)で存在するため統一。`highRiskHourWarning(ctx)` を `govAgencyImpersonationWarning(ctx)` と同型の共有ヘルパーに抽出し、Pause 分岐からも Layer 15 からも同じ関数を呼ぶ構造に。発信警告は `decide()` の外の構造的に異なるコードパスのため対象外のまま。テスト: `CallDecisionTest.high_risk_hour_warning_survives_pause` / `high_risk_hour_warning_still_absent_while_paused_outside_peak_hours`
- `FamilyCallback.MAX_SLOTS = 3` の根拠コメントなし → 真の選定理由は記録なく不明だが、変更の安全性(マイグレーション不要)と論点(UX トレードオフ)を KDoc に明記
- `"family_"` SharedPreferences キープレフィックスが `SilentBlockerService`/`ManualBlock`/`SettingsActivity` の3ファイルに生文字列で重複 → `FamilyCallback.KEY_PREFIX` を `internal` 化し3箇所とも参照に統一
- **市販品質監査(リリース準備)で発見**: zh/ko ロケールが en/ja に対し21キー欠落(Settings のブロック/許可 UI・税務署偽装警告・緊急発信警告が英語フォールバック表示) → `values-zh`/`values-ko` の `strings.xml` に21キーを追加、4ロケールでキー集合が一致することを検証済み
- **市販品質監査で発見**: `minSdk=24` なのにランチャーアイコンが `mipmap-anydpi-v26`(adaptive icon)のみで API 24-25 端末にアイコンが解決されない。さらに既存の `res/drawable/ic_launcher.xml`(pre-O fallback のつもりで置かれていた)は `mipmap` と `drawable` が別リソース型のため実際にはマニフェストの `@mipmap/ic_launcher` 参照を一切満たしておらず死んだファイルだった → `res/mipmap-(m|h|xh|xxh|xxxh)dpi/ic_launcher.png` を実データとして生成・配置(既存ベクターの背景色 #FF8C42・白円 22/108 比率を再現)。`drawable/ic_launcher.xml` のコメントも誤りを訂正
- **市販品質監査で発見**: `docs/play_data_safety.json` の `permissions_requested` に実際は要求していない `READ_CALL_LOG` を過剰申告 → 削除し、実マニフェストの2権限(`POST_NOTIFICATIONS`/`RECEIVE_BOOT_COMPLETED`)と一致させた
- `play_data_safety.json` のその他の記述陳腐化(「15-point decision engine」表記、`TaxAgencyDirectory`/`ManualBlock`/`AllowSuffixStore` 未掲載、`BusinessDirectoryBundle` 等のエントリ数の乖離)→ 全項目を実コードと突き合わせて更新: decision engine を「16-layer」表記に修正、`TaxAgencyDirectory`(1件)と `orange_tax_warn` チャンネル・緊急発信警告を追記、新設の `user_controls` セクションに `ManualBlock`/`AllowSuffixStore` を追加、エントリ数を実カウントに更新(BusinessDirectoryBundle 29→74、PoliceStationDirectory 47→54、ScamPrefixSeed 8→19、CaribbeanPremiumNANP 22→23)
- **ユーザー発見**: `play_data_safety.json` の `privacy_policy_url` に実在しないプレースホルダードメイン(`https://<github-pages-hostname>/privacy`)が入っていた → 実際のURLが確定するまでの明確な「未設定」マーカーに置き換え。Play Console 提出前に `docs/privacy_policy.html` を実際にホスティングし、本物のURLをここに記入する必要がある旨を明記
- **重大**: `RepeatCallerTracker` によるサイレント化(`SilentBlockerService.screenIncoming()`)が `decide()` の**外側**、Pause状態を確認する前に実行されており、「Pause means every call rings」という `decide()` Layer 2 の明示的契約に反していた(Pause中でも同一番号の4回目以降の着信が無音ブロックされ得た)。今セッション既に修正した政府偽装警告・高リスク時間帯警告の Pause 非一貫性(§2-3, 解消済み)と同種のバグ。速度トラッキングの継続性のため `RepeatCallerTracker.record()` は引き続き実行するが、`isRepeatOffender()` の判定結果に基づく `SILENCE` は Pause 中は発動しないようゲートを追加(`isPausedNow` を1回計算し `CallState.pausedUntilMillis` 構築とも共有)。`screenIncoming()` は Android `Context` 依存のため JVM 単体テスト対象外(CLAUDE.md の既存制約どおり)で、直接テストは追加できず目視検証のみ
- **2-1 部分対応**: 通知チャンネル8種の重複排除がない問題のうち、最も具体的だった1件(「警察/税務署/高リスク時間帯の見出し警告」→ 直後に `PostCallAdvisor` が別文言の #9110 案内を同じ通話に対してもう一度出す)を解消。`WarningNotifier.recordWarningShown()`/`wasWarnedRecently()`(10分ウィンドウ)を追加し、`showPoliceWarning`/`showTaxAgencyWarning`/`showHighRiskHourWarning` が発火時刻を記録、`PostCallAdvisor.maybeShow` が全バリアントで直近発火を確認して自身の通知をスキップ。**意図的に汎用フレームワーク化はしていない**——`TrustNotifier`(トラスト通知)と `WeeklyDigest`(週次サマリ)は対象外のまま。前者は個別ブロックの都度必要な文脈が異なり、後者は即時性のある警告と競合する「同一瞬間の重複」ではなく後日のまとめなので、同じ意味での重複ではない。テスト: `WarningNotifierRateLimitTest` に `wasWarnedRecently`/`pruneStaleRateLimitKeys` の新規ケースを追加
- 月次ダイジェスト(9週目以降)が約4-5週間分累積したブロック数を「今週 N 件」と誤表示 → `digest_text_monthly`(「今月 N 件」)を4ロケールに追加し、`WeeklyDigest.showDigest()` に `isMonthly` フラグを配線。HONESTY_ADDENDUM の誇張禁止原則との矛盾解消
- `SpamCache` 冒頭 KDoc が「LRU eviction」と主張していたが実装は純粋な FIFO(`add()` は既存ハッシュで早期 return し順序を更新しない。`add()` 自身のコメントは正しく FIFO と記述)→ 冒頭を FIFO に訂正し、LRU でないことの実害が無視できる理由(MAX_ENTRIES=10,000)も明記
- **全ソースファイル精査完了**(2026-07 監査第2巡): `OutboundGuard` / `WangiriTracker` / `PauseTile` / `TrustNotifier` / `RestoreReceiver` / `NotificationRateLimiter` / `DomesticSpoofDetector` / `SpamCache` / `AllowSuffixStore` / `PhoneNumbers` / `EmergencyWhitelist` を精読し、上記2件以外は契約と実装の一致を確認。特筆: `RestoreReceiver` が `RepeatCallerTracker.clear` / `WangiriTracker.forget` を呼ばないのは一見漏れに見えるが、Restore 後は outbound-known への追加により `screenIncoming` の trusted-set 早期 RING が両判定より先に走るため実害なし(仕様として許容)
- **UI層(Compose Activity/Widget)の精査完了**、冒頭KDocの陳腐化3件を訂正(いずれも動作は変えず、コメントを実装に合わせた):
  - `HistoryActivity.kt`: 冒頭コメントが「Allow は SPAM_CACHE/REPEAT_CALLER/WANGIRI_CALLBACK/FOREIGN_GENERIC/FOREIGN_ELEVATED の5種のみ許可」という許可リスト方式を主張していたが、実際の `HistoryCard.canAllow` は `WITHHELD_NUMBER`/`DOMESTIC_SPOOF` の2種のみ除外する拒否リスト方式(`CARRIER_VERIFICATION_FAILED`/`PREMIUM_RATE_INTERNATIONAL`/`MANUAL_BLOCK` 等も Allow 可能)。ローカルコメントは正確だったため、それに合わせて冒頭を訂正
  - `SettingsActivity.kt`: 冒頭コメントが「唯一の設定可能項目は家族番号3件」と主張していたが、実際は手動ブロック(`ManualBlock`)と許可済み番号管理(`AllowSuffixStore`)も同画面に存在(いずれも別コミットで追加済み、冒頭コメント未更新のまま放置されていた)。3セクションそれぞれの存在意義を明記する形に訂正
  - `OrangeWidget.kt`: 冒頭コメントが「タップは何もしない」と明記していたが、実装は `setOnClickPendingIntent` で明確にタップ時の遷移(ロール保持時は履歴画面、ロール喪失時は再オンボーディング)を持つ。「メニューは開かない、常に1つの遷移のみ」という実際の制約に沿って訂正
- **テストスイートを初めて実行**(§1-6)し、陳腐化テスト1件を修正: `PoliceStationDirectoryTest.decide_rings_with_no_warning_while_paused_for_non_gov_number` は `nowMillis = 1_000_000L`(= 木曜 09:16 JST = アポ電高リスク窓)+ 番号 `09099998888`(未知の携帯)を使い「Pause 中は無警告で鳴る」を検証していたが、今セッションの意図的変更「高リスク時間帯警告は Pause 中も残す」により、この番号・時刻だと正しく `HIGH_RISK_HOUR_DOMESTIC` 警告が出るようになり**テストが失敗**していた。テストの意図(Pause 中の通常番号は無警告)を保つため `nowMillis` を `79_200_000L`(= 木曜 22:00 JST = 非高リスク)に変更し、なぜ時刻が重要かをコメントで明記。`tools/run-pure-tests.sh` で修正後 199 tests / 2 failures(残り2件は §1-7 の設計判断項目)を確認
- **`tools/run-pure-tests.sh` 追加 + ストア層へ拡張**: Gradle 同梱の kotlinc + JUnit で SDK 無しでテスト実行する再利用可能スクリプト。最小 Android 型スタブ(heredoc 生成・非コミット)で SharedPreferences 依存のストア層も含め **285 tests** を実行(§1-6 参照)
- **ストア層テストの初回実行で脆いテスト3件を修正**(いずれも本番コードは正しく、非現実的なタイムスタンプが原因。独立プローブで確認):
  - `NotificationRateLimiterTest.backward_clock_jump_resets_window`: タイムスタンプ 500/1000 が極小すぎて `KEY_WINDOW_START`(既定0L)が anchor されず(本番の実 epoch なら初回で anchor)、逆行クロック検出 `nowMs < windowStart` が発火しなかった。realistic base(`1_700_000_000_000L`)に変更 → 合格。
  - `WangiriTrackerTest.forget removes entry` / `forget with E164 ...`: `forget()` は `snapshot(nowMs)` 経由で6h窓外を先に除去するため、既定の `System.currentTimeMillis()`(実2026)では固定2023エントリを消せなかった。姉妹の合格テスト同様に**明示 nowMs**(`t0 + 1`)を渡すよう変更 → 合格。
- **設計矛盾テスト1件を実挙動へ修正**: `BlockHistoryStoreTest.short number masked correctly` は `mask("110")` に `"****"` を期待していたが、`PhoneNumbers.mask()` は短縮番号(≤4桁)を**意図的にそのまま表示**する(「110/119 は公開情報・非PII」と KDoc に明記)。テスト名を `short number shown in full, not masked` に変更し `"110"` を期待するよう修正(110 は緊急番号で Layer 1 で鳴り実際には履歴記録されない moot 入力だが、マスク契約を検証する意味は残す)。**gap 隠蔽ではなく、文書化された意図的挙動へのアラインメント**
- 🔴 **【最重要・製品機能不全】`POST_NOTIFICATIONS` が実行時に一度も要求されていなかった** → `OnboardingActivity` に要求フローを追加。`targetSdk = 35`(API 33+)ではこの権限は**実行時許可必須・デフォルト拒否**なのに、全ソースに `requestPermissions`/`checkSelfPermission`/`ActivityResultContracts.RequestPermission` が皆無だった(唯一の `ActivityResultContracts` はロール取得用の `StartActivityForResult`)。結果 **Android 13+ の新規インストールでは全通知が一切表示されない**。警察/税務署偽装は「鳴らすが警告する」設計(`WarningNotifier` の warn-but-never-block)なので、警告が出ない = **偽装された警察の電話がただ鳴るだけ**——被害額7割を占める手口に対する中核防御が沈黙していた。修正: ロール取得が片付いた直後(`finishToSilent()` 冒頭、Activity 生存中)に API 33+ かつ未許可なら要求し、許可/拒否どちらでも `completeSetup()` で従来のオンボーディング完了処理へ進む(拒否してもブロック機能自体は動くため中断しない)。
- ✅ **【負の価値の削除】`SESSION_SUMMARY.md` を削除**: 過去セッションのスナップショットがリポジトリ直下に残っており、**見出し数値が全て陳腐化**していた(Commits: 13 → 実際 68 / Test count: 378 → 実際 539 @Test / CI gates: 10/10 → 実際 11/11、しかも CI 自体が当時存在しなかった)。README と並ぶ位置にあるため新規読者が現状と誤読する**負の価値**。内容は `CHANGELOG.md`(保守されている)と本ファイル(保守されている)に上位互換で存在し、git 履歴にも残るため情報損失はゼロ。必須ドキュメント一覧にも含まれていなかった。
- ✅ **【保護停止の不可視】§1-11 WeeklyDigest がロール喪失を1回だけ通知** → `WeeklyDigest.onReceive` の `if (!RoleMonitor.isRoleHeld(ctx)) return` を分岐に置換。Android は third-party にロール失効を通知しない(`addOnRoleHoldersChangedListener` は signature 権限 `MANAGE_ROLE_HOLDERS` が必要)ため、このアラームが**唯一の定期的な検知機会**であり、従来はそれを最も知らせるべき状態で捨てていた。
  - **エピソードにつき1回だけ**(`KEY_ROLE_LOST_NOTIFIED`、ロール復帰時にクリアして次回の喪失で再武装)。毎週の nag にしない根拠は**警告への慣れ(habituation)が実測された神経的効果**であること: BYU Neurosecurity / MIS Quarterly 2018 "Tuning Out Security Warnings" は fMRI で反復警告への視覚処理応答が急減することを示し、同グループは**日常的な非セキュリティ通知への慣れがセキュリティ警告の遵守率低下に般化する**ことも報告している。つまり「まだ止まっています」を毎週出すと、それ自体が無視されるだけでなく、**Orange の本体である詐欺警告への注意まで削る**。
  - **通知面は増えない**: 既存 `orange_digest` チャンネル・既存 `NOTIF_ID`・既存アラームを再利用し、**内容だけが状態に応じて変わる**。新チャンネル・新設定画面・新権限はゼロなので、この項目が判断待ちだった理由(「通知を増やさない」哲学との衝突)は発生しない。
  - `installTs == 0L`(一度もオンボーディングしていない)なら何もしない。ロール保持時の従来動作は完全に不変。
  - `showDigest` と共通の `ensureDigestChannel()` に切り出し(重複排除)。ロケール4言語に `digest_role_lost_text` を追加(87→88キー)。
  - **検証**: `WeeklyDigest` は BroadcastReceiver で `run-pure-tests.sh` の対象外のため、専用スタブを書いて**単独で型チェック(29クラス生成・エラーゼロ)**し、状態遷移6通り(保持/喪失1回目/喪失継続/再付与/再喪失/未オンボーディング)を机上トレースで確認。
- ✅ **【誤爆の恒久化】§1-8 SpamCache に TTL を導入(状況依存の判断のみ失効)** → `CallDecision.kt` に `isExpiringSilence()` を新設し、`isCacheableSilence()` が「記憶してよいか」を答えるのに対し、こちらが「その判断はいつまで真か」を答える二段構えにした。分類は **番号そのものの恒久的性質**(`DOMESTIC_SPOOF`=番号計画違反、`PREMIUM_RATE_INTERNATIONAL`=番号帯の割当、`FOREIGN_ELEVATED`、`WANGIRI_CALLBACK`)=**失効しない** と、**その時点の状況判断**(`CARRIER_VERIFICATION_FAILED`=キャリア設定次第、`FOREIGN_GENERIC`=単にその時点で発信履歴に無かっただけ)=**180日で失効**。ユーザーの明示的意思(`SPAM_CACHE`/`MANUAL_BLOCK`)は当然**失効しない**(意図的に伝えたことを時間で忘れるのは別のバグ)。`isCacheableSilence` と同じ網羅的 `when` にしたので、新しい `BlockReason` を足すとコンパイルエラーで分類を強制される。
  - **保存形式**: `KEY_ORDER` のトークンを `hash`(恒久・従来形式)または `hash|失効エポックms` に拡張。読み取りは両形式を許容するので、**失効機能より前のインストールはマイグレーション不要でそのまま動き、既存エントリは恒久のまま**。
  - **180日の根拠**: FCC は米国で年約3,500万件(全番号の約10%)の再割当・最短45日のエージング期間、総務省は未使用JP携帯に約3年を掲げる一方、実際の解約→再利用は数ヶ月との報告がある。半年は「詐欺師が使い続けている番号には fast-path が効く」一方「捨てられた番号が次の持ち主を黙らせ続けない」境界。
  - **`contains()` で prune も実施**: 失効エントリはヒットしないだけでなく **KEY_SET/KEY_ORDER から実際に削除**される(失効トークンが存在しない場合は読み取りのみで書き込まない)。これは §1-10 への直接の効果でもある — ホットパスのコストはキャッシュ規模に比例するため、**上限10,000件まで単調増加する前提そのものを崩す**。
  - **逆行クロックは「失効させない」方向に倒す**(`nowMs < stamp` は未失効扱い)。時計が巻き戻ったせいで詐欺師のブロックが突然解けるより、静音を続ける方が安全。
  - **`remove()` を失効トークン対応に修正**: 従来は生ハッシュで `order.remove()` していたため、`hash|ts` 形式のトークンを削除できず Restore が半分しか効かない状態になるところだった(`hashOf()` で比較するよう修正)。
  - テスト9件追加(`SpamCacheTest`): TTL 前後の境界、恒久エントリが TTL の10倍でも残る、既定引数が従来どおり恒久、失効時に実際に集合から消える、恒久と失効の混在で失効分のみ落ちる、逆行クロック、失効トークンの `remove()`、**旧形式(タイムスタンプ無し)エントリが恒久として読める後方互換**。276→**285 tests**。
- 🔴 **【安全性】応答順序の逆転と例外の無防備** → `SilentBlockerService.onScreenCall` を修正。従来は `handleDecision()`(全副作用)を完走してから `respond()` を呼んでおり、かつ `onScreenCall`/`screenIncoming`/`handleDecision` に try/catch が皆無だった(唯一の catch は `TrustNotifier` 周りのみ)。副作用側には throw 源が複数ある(`WarningNotifier.show*Warning` は TrustNotifier と違い無防備、`refreshTiles()` の `TileService.requestListeningState` はタイル無効時に例外、`sendBroadcast`、prefs 破損時の `getStringSet` の `ClassCastException`)。例外が Binder コールバックを抜けるとプロセスクラッシュ → `respondToCall` が呼ばれず、**Telecom のスクリーニング期限切れまで着信が遅延**し、原因が持続的なら以後の着信でも毎回再発。さらに SILENCE 経路では `OutboundGuard.record`/`SpamCache.add`/`BlockHistoryStore.record` が既にコミット済みなのに応答だけ返らない**部分コミット**になっていた。修正: 判定確定直後に `respond()` を先に呼び、`handleDecision()` を try/catch で包む。`screenIncoming()` 自体も try で包み、失敗時は `Decision(Verdict.RING)` で**フェイルオープン**(静音より鳴らす方が安全、EmergencyWhitelist の論拠と同じ)。OUTGOING 経路も同様に respond 先行 + `handleOutgoing` を catch。**`CallDecision.decide()` は一切変更していない**(判定ロジックは不変、順序と防御のみ)

---

## 5. 対応推奨順

1. ~~**1-8**(SpamCache に TTL 無し)~~ — **✅ 2026-07 解決済み**。`isExpiringSilence()` で状況依存の判断のみ180日失効。§4「解消済み」参照。副次的に §1-10 のキャッシュ規模問題も緩和
2. **1-9**(salt 回転で信頼集合が黙って失効)— **2026-07 再評価で優先度低下**。認証要求なしの鍵なのでロック/生体変更では失効せず、device-transfer も全除外のため機種変更シナリオは発生しない。残る Keystore 破損ケースも §1-8 の TTL で最長180日で自動回復。記録のみで据え置き可
3. **1-7**(DomesticSpoofDetector の棄権挙動 + 失敗する2テスト)— 実行して初めて分かる矛盾。numbering-plan 厳格性の設計判断。ユーザー確認推奨
4. ~~**1-11**(ロール失効が実質伝わらない)~~ — **✅ 2026-07 解決済み**。WeeklyDigest が喪失エピソードごとに1回通知(通知面は不変)。§4「解消済み」参照
5. **1-10**(コールドスタート時の Keystore/prefs がホットパス)— 5秒デッドラインに対するリスク。**§1-8 の TTL 導入で規模の前提が崩れ、`KEY_ORDER` 統合案も実測で却下**したため、残るは warmup 追加の是非のみ(小)
6. ~~**1-2**(CSV 監査)~~ — **✅ 2026-07 解決**。A/B/C の3条件基準を確立し、現状配置(警察・税務署のみ warn)が正しいと確認。宅配は攻撃ベクターが SMS のため音声側 warn 化は無効、銀行は代表番号偽装が手口の中核でない
7. **1-5 / 2-2 / 2-4** — 製品哲学・文言とのトレードオフ。**必ずユーザーに確認してから着手**
8. `play_data_safety.json` の `privacy_policy_url` — Play Console 提出前に `docs/privacy_policy.html` の実ホスティング先URLを確定して記入すること(現状「未設定」マーカー)
9. **CI で実テスト実行**(§1-6)— `.github/workflows/` 復活時に `tools/run-pure-tests.sh`(+ SDK 有りなら `./gradlew testReleaseUnitTest`)を組み込み、「@Test を数えるだけ」の状態を解消すること

低リスクな解消(ドキュメント整備・整合性確認・市販品質監査の機械的修正・具体的に特定できた1件の通知重複・陳腐化テスト1件・テスト実行基盤・**POST_NOTIFICATIONS 未要求と応答順序という2つの重大バグ**・**§1-5 脅威モデルの明示**・**§1-8 の TTL 導入**・**§1-11 のロール喪失通知**)は完了済み。残る項目は全て要ユーザー判断。

> **First Principles 監査(2026-07)の総括**: 「高齢者が詐欺で金を失うのを防ぐ」という第一原理から要件を導出し実装と突き合わせた結果、**機能の不足ではなく、実装済み機能が届かない経路**に最大の欠陥があった。すなわち (a) 通知権限を要求しないので警告が誰にも届かない、(b) 副作用の例外で応答が返らず着信が遅延する、(c) 一度の誤判定が TTL 無しで恒久化する、(d) 保護が消えたことに気づけない。(a)(b) は機械的に修正済み。(c)(d) は §1-8/§1-11 として判断待ち。**新機能の追加ではなく既存機能の到達性の確保が、この製品の次の改善軸**。
