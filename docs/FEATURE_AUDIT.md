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

### 1-5. 信頼済みインサイダー脅威モデルの欠落【脅威モデル課題】
- `SaltVault`(Keystore 暗号化)は端末外・遠隔の攻撃者向け。高齢者ユーザーの現実的脅威である「ロック解除済み端末を手にした家族・介護者」(financial elder abuse の主要ベクター)への防御・言及がコードにもドキュメントにもない。
- 対策はアプリ内 PIN 等になるが「設定画面を増やさない」という製品哲学と衝突する。**実装前にユーザー(プロダクトオーナー)の判断を仰ぐこと**。

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

---

## 5. 対応推奨順

1. **1-2**(CSV 監査)— 機関ごとの個別判断。ユーザー確認推奨
2. **1-5 / 2-2 / 2-4** — 製品哲学・文言とのトレードオフ。**必ずユーザーに確認してから着手**
3. `play_data_safety.json` の `privacy_policy_url` — Play Console 提出前に `docs/privacy_policy.html` の実ホスティング先URLを確定して記入すること(現状「未設定」マーカー)

低リスクな解消(ドキュメント整備・整合性確認・市販品質監査の機械的修正・具体的に特定できた1件の通知重複)は完了済み。残る項目は全て要ユーザー判断。
