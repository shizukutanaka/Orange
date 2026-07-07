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

### 1-5. 信頼済みインサイダー脅威モデルの欠落【脅威モデル課題】
- `SaltVault`(Keystore 暗号化)は端末外・遠隔の攻撃者向け。高齢者ユーザーの現実的脅威である「ロック解除済み端末を手にした家族・介護者」(financial elder abuse の主要ベクター)への防御・言及がコードにもドキュメントにもない。
- 対策はアプリ内 PIN 等になるが「設定画面を増やさない」という製品哲学と衝突する。**実装前にユーザー(プロダクトオーナー)の判断を仰ぐこと**。

### 1-6. play_data_safety.json の記述陳腐化(READ_CALL_LOG 過剰申告は解消済み、残りは未着手)【低リスク・機械的更新可】
- **場所**: `docs/play_data_safety.json`。
- **解消済み**: `permissions_requested` の `READ_CALL_LOG` を削除し、実際のマニフェスト権限(`POST_NOTIFICATIONS`/`RECEIVE_BOOT_COMPLETED`)と一致させた。
- **未着手の残課題**(今回のプランの意図的スコープ外、機械的に直せるが未対応):
  - `components.services` の説明が「15-point decision engine」と記載 — 実際は `CallDecision.kt` の `decide()` は Layer 16(+ Layer 9b)まで存在。
  - `components.offline_databases` に `TaxAgencyDirectory` が未掲載(警察庁/国税庁の警告レーン化は今セッションで実装済みなのに反映漏れ)。
  - `components.notification_objects` に `orange_tax_warn` チャンネルと緊急発信警告(`outbound_warn_title_urgent`)が未掲載。
  - `components` 全体に `ManualBlock`(手動ブロック機能)の記載がない。
  - `BusinessDirectoryBundle` のエントリ数「29」が現状の CSV エントリ数(約75)と乖離している可能性(要再カウント)。
- **対応方針**: Play Console 申告としての法的/審査リスクがあるのは権限一致(解消済み)のみ。残りは開発者向けドキュメントの正確性の問題であり、次回このファイルを触るときにまとめて更新するのが効率的。

---

## 2. 過剰な機能(excesses)— 統合・削減候補

### 2-1. 通知チャンネル8種の増殖、兄弟チャンネル間の重複排除なし
- **場所**: `WarningNotifier.kt`(police / tax / highrisk / outbound)、`TrustNotifier.kt`(trust / ongoing)、`PostCallAdvisor.kt`(postcall)、`WeeklyDigest.kt`(digest)。
- **問題**: 各通知は**自チャンネル内でのみ**レート制限(例: `highrisk_last_*` / `outbound_warn_ts_*` / `postcall_last_*` キー)しており、兄弟チャンネルとの重複排除がない。1つの不審着信で「高リスク時間帯警告 → 通話後アドバイザリ → 週次ダイジェスト」と同一イベント由来の通知が複数届き得る。
- **注意**: 各チャンネルには個別の設計根拠が KDoc に書かれている(安易に統合しない)。修正するなら「同一番号について直近 N 分内に別チャンネルが発火していたら抑制する」ような横断デデュープ層の追加が候補。

### 2-2. 解除メカニズムの二重化(Allow vs Restore)
- **Allow**(`HistoryActivity` → `AllowSuffixStore`): 末尾4桁サフィックス一致。履歴がマスク番号(`****1234`)しか持たないための妥協。
- **Restore**(通知 → `RestoreReceiver`): 完全番号で `SpamCache.remove` + 発信済みセット追加 + `OutboundGuard.forget`。
- **問題**: ユーザーには同じ「ブロック取り消し」なのに、場所も保証も違う2つのボタン。ストレージ形式の実装都合が製品表面に漏れている。
- **注意**: 統一するには History にハッシュを保存する変更が必要で、これは意図的なプライバシー設計(`BlockHistoryStore` KDoc「display-only」)とのトレードオフ。**設計判断が必要、機械的修正不可**。

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
- **市販品質監査で発見**: `docs/play_data_safety.json` の `permissions_requested` に実際は要求していない `READ_CALL_LOG` を過剰申告 → 削除し、実マニフェストの2権限(`POST_NOTIFICATIONS`/`RECEIVE_BOOT_COMPLETED`)と一致させた(残る記述陳腐化は §1-6 参照、意図的に対応保留)

---

## 5. 対応推奨順

1. **2-1**(通知の横断デデュープ)— 設計判断込み。ユーザー確認推奨
2. **1-2**(CSV 監査)— 機関ごとの個別判断。ユーザー確認推奨
3. **1-6 残課題**(play_data_safety.json の記述更新)— 低リスク、次回このファイルを触る時にまとめて対応
4. **1-5 / 2-2** — 製品哲学とのトレードオフ。**必ずユーザーに確認してから着手**

低リスクな解消(ドキュメント整備・整合性確認・市販品質監査の機械的修正)は完了済み。残る項目は全て要ユーザー判断、または次回まとめ対応で十分なもの。
