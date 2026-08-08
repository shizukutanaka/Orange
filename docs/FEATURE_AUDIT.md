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

### 1-2. business_directory.csv 残り約73エントリの偽装リスク監査【要機関別判断】
- **場所**: `app/src/main/assets/business_directory.csv`(Layer 5 = 無条件サイレント信頼。警告なしで着信、STIR/SHAKEN 失敗すら無視)。
- **経緯**: 警察庁・国税庁がこのバンドルに誤って入っていた(= 偽装されても警告ゼロ)バグを発見・修正済み。警察庁 → `PoliceStationDirectory.kt`、国税庁 → `TaxAgencyDirectory.kt` に移し、「鳴らすが警告する」レーンへ変更した。
- **残課題**: 宅配業者(ヤマト・佐川 = 不在通知詐欺)、メガバンク(不正送金詐欺)等、偽装頻度の高い機関が今もサイレント信頼のまま。ただし警察/税務署と違い**正当な着信量が多い**ため、warn-but-ring 化はカスタマーサービス着信に摩擦を生む。機関ごとに判断が必要で、機械的に移せない。
- **回帰ガード**: `BusinessDirectoryBundleTest.shipped_csv_never_bundles_a_warn_directory_number` が `PoliceStationDirectory` / `TaxAgencyDirectory` の**全エントリ**を CSV と突き合わせる汎用テスト。新しい warn ディレクトリを作ったら、このテストの `warnDirectories` リストに追加すること。

### 1-3. ディレクトリ陳腐化への構造的対策【設計課題】
- **現状**: `ProtectionDataVersion.LAST_UPDATED`(= "2026-04"、**全ディレクトリ中の最古の確認日**を採用)を Settings フッターに表示するところまで実装済み。
- **未解決**: オフライン完結の約束ゆえ、更新手段はアプリ更新のみ。「最新の詐欺手口に追従」との緊張関係は構造的に残る。ネットワーク追加は不変制約違反なので**選択肢にない**。取りうる手はリリースサイクルの短縮か、表示の充実のみ。
- **注意**: ディレクトリ(`PoliceStationDirectory` / `TaxAgencyDirectory` / `business_directory.csv` / `ScamPrefixSeed` / `EmergencyWhitelist`)のデータを更新したら `LAST_UPDATED` も更新する。新しい日付ではなく**最古の検証日**を維持する規約(鮮度の誇張を防ぐため)。

### 1-4. 警告通知の実効性が未検証【UX 研究課題、コード修正ではない】
- 警察/税務署偽装・高リスク時間帯の警告は**通話応答後**に notification として届く。詐欺師と通話中の被害者に通知が届くという介入方法が行動を変えるかは未検証(オフライン設計ゆえテレメトリでの検証も不可能)。
- 改善候補: 通知の文言・タイミング・フルスクリーンインテント化などがあり得るが、いずれも設計判断が必要。
- **2026-07 調査で得たエビデンス**: CHI 2025 論文 "It Warned Me Just at the Right Moment"(arXiv:2502.03964)は、**通話中のリアルタイム警告(画面表示+振動)が詐欺師の心理的圧力を分断し、早期介入ほど不可逆な送金判断を防ぐ**ことを 20名の被験者実験で示した。これは「フルスクリーンインテント化」候補を裏付ける初のエビデンス。ただし論文の手法は録音+外部LLM前提であり、Orange の `RECORD_AUDIO`/`INTERNET` 禁止制約とは相容れない — Orange が取れるのは「着信メタデータに基づく事前警告の提示強度を上げる」ところまで。**通知の攻撃性が上がる(高齢者を焦らせる/誤警告時の負荷)トレードオフがあるため、実装は引き続きユーザー判断**。関連する外部指標として、警視庁は都内ニセ警察詐欺の前年比38.8%減を防犯アプリ利用増と相関づけている(時事 2026-07-06) — 本カテゴリの介入が有効という状況証拠。
- **2026-07 追加調査 — 警告の「文言」に関する直接的エビデンス(実装反映済み)**: IEEE S&P 2025 / arXiv:2412.04014 *"(Blind) Users Really Do Heed Aural Telephone Scam Warnings"*(CISPA、盲導ユーザー36名 + 晴眼者36名を実際にコールドコールする自然主義的実験)は、警告を **baseline(なし) / short warning / contextual warning(詐欺の内容に先立って「これから何が起こるか」を具体的に説明)** の3条件で比較し、**contextual warning を聞いた晴眼者は全員が電話を切った**と報告している(指示どおり「1を押した」のは2名のみで、うち1名はスクリーンリーダーの操作性問題、もう1名は「詐欺師の時間を浪費させるため意図的に」)。すなわち**「怪しい」と伝えるだけの短い警告より、次に何が起きるかを予告する文脈的警告の方が行動変容に効く**。
  - これを受けて `police_warn_body` を short warning 型(「偽装の可能性。一度切って #9110」)から **contextual 型**(「本物の警察がLINEやビデオ通話に誘導したり、お金の話をすることはありません。あれば詐欺です」)へ4ロケール全て改訂。手口の具体は `PoliceStationDirectory.kt` の KDoc に記録済みの実手口(**折り返させて LINE/ビデオ通話へ誘導**)に基づく。`tax_warn_body` は元から contextual 型(「税務署が電話で支払いを求めることはありません」)だったため変更なし — **被害額の約7割を占めるニセ警察詐欺の側が弱い形式だった**のを揃えた形。
  - **これは通知の攻撃性(フルスクリーン化・音・振動)を一切上げない**ため、上記のトレードオフ判断を要さない。文言のみの変更で、論文が最も効果的と示した形式に寄せた。

### 1-5. 信頼済みインサイダー脅威モデルの欠落【脅威モデル課題】
- `SaltVault`(Keystore 暗号化)は端末外・遠隔の攻撃者向け。高齢者ユーザーの現実的脅威である「ロック解除済み端末を手にした家族・介護者」(financial elder abuse の主要ベクター)への防御・言及がコードにもドキュメントにもない。
- 対策はアプリ内 PIN 等になるが「設定画面を増やさない」という製品哲学と衝突する。**実装前にユーザー(プロダクトオーナー)の判断を仰ぐこと**。

### 1-6. テストスイートがCIで一度も実行されていなかった【プロセス課題 + 実バグ5件発覚】
- **発覚(2026-07)**: `.github/workflows/` は `.gitignore` で除外されており、静的ゲート `check_comprehensive.sh` は `@Test` アノテーションを**数えるだけ**でテストを**実行しない**。つまり JVM ユニットテストは自動実行された実績が皆無だった。SESSION_SUMMARY.md 自身も過去に「static CI never caught them (only counts @Test annotations)」と言及していた。
- **今セッションで初めて実行**: Gradle ディストリビューション同梱の `kotlin-compiler-embeddable` + `junit-4.13.2` を使い、Android SDK 無しで `tools/run-pure-tests.sh` を追加。当初は Android 非依存サブセット(199 tests)のみだったが、**最小の Android 型スタブ(SharedPreferences を Java で書き platform type を再現 / Base64 / keystore 型 / `edit` 拡張。ロジックなし、`/tmp` 限定・非コミット)を追加してストア層(SpamCache/OutboundGuard/WangiriTracker/RepeatCaller/RateLimiter/AllowSuffix/BlockHistory)も対象に拡大**。現在 **17 main sources + 17 test files, 276 tests** を実行。**NotificationManager/NotificationCompat/Context/Activity/Service/Widget 依存(WarningNotifier, ManualBlock, FamilyCallback, TrustNotifier, BusinessDirectoryBundle, UI 各種)は依然として対象外** — 通常の `./gradlew testReleaseUnitTest` が必要。
- **合計5件の失敗を検出**(全て「独立した FakePrefs 実装での直接プローブ」でシム副作用でないことを裏取り済み):
  - 1件: 今セッションの意図的変更(高リスク時間帯警告が Pause 中も残る)による陳腐化テスト → 修正済み。
  - 3件: **非現実的なタイムスタンプに起因する脆いテスト**(本番コードは正しいことをプローブで確認)→ 修正済み(下記「解消済み」)。
  - 2件: **DomesticSpoofDetector の設計判断**(§1-7)→ 意図的に失敗のまま残置。
- **現在の期待値**: `bash tools/run-pure-tests.sh` → **276 run / 2 failures**(2件は §1-7 のシグナル。それ以外の失敗は回帰)。スクリプトは「§1-7 の2件以外が失敗したら exit 1」の終了コード契約を持つ。
- **`.githooks/pre-push` に配線済み**: `./gradlew` + wrapper jar があれば従来どおり `testReleaseUnitTest`(全件)。無ければ(fresh clone / SDK 無しサンドボックス等)`run-pure-tests.sh` を実行して push をゲートする。これにより「テストが一度も走らないまま push される」状態を、SDK が無い環境でも部分的に解消。**CI(`.github/workflows/` 復活時)にも同じランナーを組み込むこと**(§5 対応推奨順)。

### 1-7. DomesticSpoofDetector が先頭ゼロ無し/短縮番号を棄権する【要設計判断】
- **場所**: `DomesticSpoofDetector.toDomestic()`(`app/src/main/java/com/orange/apple/DomesticSpoofDetector.kt`)。
- **事象**: `toDomestic()` は入力が `"0"` 始まりでも `"+81"` 始まりでもない場合 `null` を返し、`isImpossibleJpNumber()` は `?: return false` で即座に**棄権**(= 偽装ではない)する。この結果、後続の `d.length < 10 → true`(短すぎる)や「先頭ゼロ欠落」判定は**到達不能**になっている。
- **実測(`tools/run-pure-tests.sh`)**: `isImpossibleJpNumber("110") = false`、`isImpossibleJpNumber("9012345678") = false`。しかし `DomesticSpoofDetectorTest` の2テスト(`short_code_110_is_flagged_as_impossible_by_detector`, `missing_leading_zero_is_spoof`)は `true` を期待しており、**実行すると失敗する**(コードとテストの矛盾)。
- **論点**:
  - `"110"`: ライブエンジンでは Layer 1(EmergencyWhitelist)が処理し、この検出器には決して到達しない(テストのコメント自身が認めている)。棄権(false)は実害なしだが、テストのコメント「The detector correctly flags it」は**事実と異なる**。
  - `"9012345678"`(先頭ゼロ欠落の携帯番号): ライブエンジンでは Layer 16 まで落ちて **RING** する。これを偽装として弾くべきかは numbering-plan の厳格性に関する**製品判断**。`toDomestic()` を先頭ゼロ無し番号も通すよう変更するとエンジン全体の挙動が変わり、慎重なテストが必要。
- **対応方針**: どちらも「検出器が非ドメスティック形式の入力を弾くべきか」という設計判断。**コードを一方的に変更したり、テストの assertion を黙って反転させたりしない**(後者は潜在的な gap を隠蔽する)。失敗テストが矛盾の可視シグナルとして機能する。**要ユーザー判断**。

### 1-8. SpamCache に TTL が無く恒久ロックアウトしうる【要設計判断】
- `isCacheableSilence`(`CallDecision.kt:439-456`)は `CARRIER_VERIFICATION_FAILED` / `FOREIGN_GENERIC` / `DOMESTIC_SPOOF` で true を返す。よって **STIR/SHAKEN が壊れたキャリア経由の正当な発信者**や**正当な国際発信者**が、初回着信で全 `phoneVariants()` ぶんキャッシュされ、以後 Layer 6 の fast-path で恒久的に静音される。eviction は 10,000 件到達時の FIFO のみで **TTL なし**。
- 復旧経路はユーザーが気づいて History から Restore する導線だけ。`TrustNotifier` が `NotificationRateLimiter` で間引かれた/スワイプされた/通知が拒否されている場合、**気づく手段が実質ない**。
- **論点**: TTL や再評価の付与は「一度ブロックした番号は覚え続ける」という学習の永続性(製品の中核的価値)とのトレードオフ。**要ユーザー判断**。

### 1-9. salt 回転で信頼集合が黙って失効する【要設計判断】
- Keystore キーが無効化されると(機種変更・キー invalidation)`SaltVault.decrypt` が null を返し(`:128-130`)、平文フォールバックは前回の暗号化成功時に削除済み(`:111`)なので**新しい salt が生成**される。
- 結果、`SpamCache.hash` に依存する**発信済み集合(outbound-known)**と `RepeatCallerTracker` のハッシュが全て不一致になり、長年信頼してきた正当な国際連絡先が再び FOREIGN_* 層に落ちる → さらに §1-8 により恒久キャッシュされる。**検知も通知もログもない**(家族番号は平文保存のため無事)。
- **論点**: 検知(salt 変更の記録)と再構築(信頼集合の移行)の設計が必要。**要ユーザー判断**。

### 1-10. コールドスタート時に Keystore と prefs パースがホットパス【要設計判断】
- `EngineWarmup` は CSV と静的ディレクトリのみ warm し、`SaltVault.salt`/`SpamCache.hash` を warm しない。プロセス起動後の初回着信で **Keystore ラウンドトリップ(10–50ms、StrongBox ではさらに悪化)** を screening callback 内で払う。`SpamCache` 自身の KDoc(`:46-48`)が「これを毎回払うのは不要」と書いているのに、初回だけは実際に払っている。
- 加えて SharedPreferences の初回ロードは同期 XML パースで、`SpamCache.MAX_ENTRIES = 10,000` の64桁ハッシュ + `KEY_ORDER` の重複コピー + outbound 1,000件 = **1MB超**を screening スレッドで解析しうる。`SilentBlockerService.kt:24-25` の KDoc「disk I/O は prefs 1回読みのみ」はこの規模を過小評価している。
- **論点**: warmup への追加は容易だが、アプリ起動時に Keystore 初期化を持ち込む是非(起動コスト・キー無効化例外の扱い)は判断が要る。**要ユーザー判断**。

### 1-11. ロール失効がユーザーに実質伝わらない【要設計判断・哲学衝突】
- `RoleMonitor` は意図的に無通知(KDoc `:37-39` 明記)。シグナルはウィジェットの `·` 一文字のみ(`OrangeWidget.kt:59`)で、**ウィジェット未設置なら永久に気づけない**(`RoleMonitor.kt:63` が `ids.isNotEmpty()` で gate)。
- 検知タイミングも BOOT_COMPLETED / MY_PACKAGE_REPLACED / ウィジェット30分更新のみ。**再起動もウィジェットも無いセッション中の失効は検知されない**。
- さらに唯一の定期接点である `WeeklyDigest` が `if (!RoleMonitor.isRoleHeld(ctx)) return`(`:36`)で**自らを抑止**するため、最も知らせるべき状態でこそ沈黙する。
- **論点**: 「設定画面を増やさない/通知を増やさない」哲学と真正面から衝突。**要ユーザー判断**。

---

## 2. 過剰な機能(excesses)— 統合・削減候補

### 2-2. 解除メカニズムの二重化(Allow vs Restore)
- **Allow**(`HistoryActivity` → `AllowSuffixStore`): 末尾4桁サフィックス一致。履歴がマスク番号(`****1234`)しか持たないための妥協。
- **Restore**(通知 → `RestoreReceiver`): 完全番号で `SpamCache.remove` + 発信済みセット追加 + `OutboundGuard.forget`。
- **問題**: ユーザーには同じ「ブロック取り消し」なのに、場所も保証も違う2つのボタン。ストレージ形式の実装都合が製品表面に漏れている。
- **注意**: 統一するには History にハッシュを保存する変更が必要で、これは意図的なプライバシー設計(`BlockHistoryStore` KDoc「display-only」)とのトレードオフ。**設計判断が必要、機械的修正不可**。

### 2-4. 警察/税務署 warn-but-ring 番号へのかけ直しに発信警告が出る【要文言/挙動判断】
- **経路**: `handleDecision()`(`SilentBlockerService.kt`)は警察/税務署の偽装警告(warn-but-ring)発火時にもその番号を `OutboundGuard.record()` する。警告後にユーザーが**本物の警察署の代表番号にかけ直す**(anti-scam 指導で推奨される安全行動そのもの)と、`showOutboundWarning` が発火する。
- **問題は2面**:
  1. **文言の事実誤り**: 通知 body は「この番号は直前にブロックした番号です」(`outbound_warn_body`)だが、warn-but-ring 番号は**ブロックしておらず着信させた**番号。15分以内なら緊急版(`outbound_warn_body_urgent`「送金や暗証番号の共有をしないで」)が本物の警察署への発信に対して出る。
  2. **推奨行動への摩擦**: スプーフィングされた着信の後、表示番号に自分からかけ直せば本物の警察に着信する(発信はスプーフィングできない)。この安全な確認行動を怖い通知で妨げる形になっている。
- **反論(現状維持の根拠)**: 高齢ユーザーにとって「かけ直しの前にひと呼吸」はそれ自体が保護であり、警告は発信をブロックしない(agency は保たれる)。#9110 は `EmergencyWhitelist` 対象外だが `handleOutgoing` の emergency チェックで記録されないため #9110 への相談経路は摩擦ゼロのまま。
- **選択肢**: (a) 現状維持、(b) warn-but-ring 由来のエントリに別フラグを付け専用文言(「先ほど警告した番号です。本物の窓口なら問題ありません」)を出す、(c) warn-but-ring 番号は `OutboundGuard.record()` しない。**製品判断が必要、機械的修正不可**。

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
- **`tools/run-pure-tests.sh` 追加 + ストア層へ拡張**: Gradle 同梱の kotlinc + JUnit で SDK 無しでテスト実行する再利用可能スクリプト。最小 Android 型スタブ(heredoc 生成・非コミット)で SharedPreferences 依存のストア層も含め **276 tests** を実行(§1-6 参照)
- **ストア層テストの初回実行で脆いテスト3件を修正**(いずれも本番コードは正しく、非現実的なタイムスタンプが原因。独立プローブで確認):
  - `NotificationRateLimiterTest.backward_clock_jump_resets_window`: タイムスタンプ 500/1000 が極小すぎて `KEY_WINDOW_START`(既定0L)が anchor されず(本番の実 epoch なら初回で anchor)、逆行クロック検出 `nowMs < windowStart` が発火しなかった。realistic base(`1_700_000_000_000L`)に変更 → 合格。
  - `WangiriTrackerTest.forget removes entry` / `forget with E164 ...`: `forget()` は `snapshot(nowMs)` 経由で6h窓外を先に除去するため、既定の `System.currentTimeMillis()`(実2026)では固定2023エントリを消せなかった。姉妹の合格テスト同様に**明示 nowMs**(`t0 + 1`)を渡すよう変更 → 合格。
- **設計矛盾テスト1件を実挙動へ修正**: `BlockHistoryStoreTest.short number masked correctly` は `mask("110")` に `"****"` を期待していたが、`PhoneNumbers.mask()` は短縮番号(≤4桁)を**意図的にそのまま表示**する(「110/119 は公開情報・非PII」と KDoc に明記)。テスト名を `short number shown in full, not masked` に変更し `"110"` を期待するよう修正(110 は緊急番号で Layer 1 で鳴り実際には履歴記録されない moot 入力だが、マスク契約を検証する意味は残す)。**gap 隠蔽ではなく、文書化された意図的挙動へのアラインメント**
- 🔴 **【最重要・製品機能不全】`POST_NOTIFICATIONS` が実行時に一度も要求されていなかった** → `OnboardingActivity` に要求フローを追加。`targetSdk = 35`(API 33+)ではこの権限は**実行時許可必須・デフォルト拒否**なのに、全ソースに `requestPermissions`/`checkSelfPermission`/`ActivityResultContracts.RequestPermission` が皆無だった(唯一の `ActivityResultContracts` はロール取得用の `StartActivityForResult`)。結果 **Android 13+ の新規インストールでは全通知が一切表示されない**。警察/税務署偽装は「鳴らすが警告する」設計(`WarningNotifier` の warn-but-never-block)なので、警告が出ない = **偽装された警察の電話がただ鳴るだけ**——被害額7割を占める手口に対する中核防御が沈黙していた。修正: ロール取得が片付いた直後(`finishToSilent()` 冒頭、Activity 生存中)に API 33+ かつ未許可なら要求し、許可/拒否どちらでも `completeSetup()` で従来のオンボーディング完了処理へ進む(拒否してもブロック機能自体は動くため中断しない)。
- 🔴 **【安全性】応答順序の逆転と例外の無防備** → `SilentBlockerService.onScreenCall` を修正。従来は `handleDecision()`(全副作用)を完走してから `respond()` を呼んでおり、かつ `onScreenCall`/`screenIncoming`/`handleDecision` に try/catch が皆無だった(唯一の catch は `TrustNotifier` 周りのみ)。副作用側には throw 源が複数ある(`WarningNotifier.show*Warning` は TrustNotifier と違い無防備、`refreshTiles()` の `TileService.requestListeningState` はタイル無効時に例外、`sendBroadcast`、prefs 破損時の `getStringSet` の `ClassCastException`)。例外が Binder コールバックを抜けるとプロセスクラッシュ → `respondToCall` が呼ばれず、**Telecom のスクリーニング期限切れまで着信が遅延**し、原因が持続的なら以後の着信でも毎回再発。さらに SILENCE 経路では `OutboundGuard.record`/`SpamCache.add`/`BlockHistoryStore.record` が既にコミット済みなのに応答だけ返らない**部分コミット**になっていた。修正: 判定確定直後に `respond()` を先に呼び、`handleDecision()` を try/catch で包む。`screenIncoming()` 自体も try で包み、失敗時は `Decision(Verdict.RING)` で**フェイルオープン**(静音より鳴らす方が安全、EmergencyWhitelist の論拠と同じ)。OUTGOING 経路も同様に respond 先行 + `handleOutgoing` を catch。**`CallDecision.decide()` は一切変更していない**(判定ロジックは不変、順序と防御のみ)

---

## 5. 対応推奨順

1. **1-8**(SpamCache に TTL 無し = 正当な発信者の恒久ロックアウト)— First Principles 監査で発見。実害が最も直接的(正当な国際発信者・STIR/SHAKEN 不良キャリア経由の相手が二度と鳴らなくなる)。学習の永続性とのトレードオフ判断が要る
2. **1-9**(salt 回転で信頼集合が黙って失効)— 1-8 と連鎖して被害が拡大する。検知・再構築の設計が要る
3. **1-7**(DomesticSpoofDetector の棄権挙動 + 失敗する2テスト)— 実行して初めて分かる矛盾。numbering-plan 厳格性の設計判断。ユーザー確認推奨
4. **1-11**(ロール失効が実質伝わらない)— 保護が消えたこと自体に気づけない。「通知を増やさない」哲学と衝突
5. **1-10**(コールドスタート時の Keystore/prefs がホットパス)— 5秒デッドラインに対するリスク。warmup 追加は容易だが起動コストの判断が要る
6. **1-2**(CSV 監査)— 機関ごとの個別判断。ユーザー確認推奨
7. **1-5 / 2-2 / 2-4** — 製品哲学・文言とのトレードオフ。**必ずユーザーに確認してから着手**
8. `play_data_safety.json` の `privacy_policy_url` — Play Console 提出前に `docs/privacy_policy.html` の実ホスティング先URLを確定して記入すること(現状「未設定」マーカー)
9. **CI で実テスト実行**(§1-6)— `.github/workflows/` 復活時に `tools/run-pure-tests.sh`(+ SDK 有りなら `./gradlew testReleaseUnitTest`)を組み込み、「@Test を数えるだけ」の状態を解消すること

低リスクな解消(ドキュメント整備・整合性確認・市販品質監査の機械的修正・具体的に特定できた1件の通知重複・陳腐化テスト1件・テスト実行基盤・**POST_NOTIFICATIONS 未要求と応答順序という2つの重大バグ**)は完了済み。残る項目は全て要ユーザー判断。

> **First Principles 監査(2026-07)の総括**: 「高齢者が詐欺で金を失うのを防ぐ」という第一原理から要件を導出し実装と突き合わせた結果、**機能の不足ではなく、実装済み機能が届かない経路**に最大の欠陥があった。すなわち (a) 通知権限を要求しないので警告が誰にも届かない、(b) 副作用の例外で応答が返らず着信が遅延する、(c) 一度の誤判定が TTL 無しで恒久化する、(d) 保護が消えたことに気づけない。(a)(b) は機械的に修正済み。(c)(d) は §1-8/§1-11 として判断待ち。**新機能の追加ではなく既存機能の到達性の確保が、この製品の次の改善軸**。
