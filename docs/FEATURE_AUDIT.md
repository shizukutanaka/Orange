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

### 2-3. Pause の影響範囲の非一貫性【現状を明文化済み・挙動変更は未判断】
- 政府偽装警告(Layer 9/9b)は Pause 中も発火するよう修正済み(`decide()` の Layer 2 が `govAgencyImpersonationWarning(ctx)` を先に照会)。
- 高リスク時間帯警告(Layer 15)は Pause 中に発火**しない**(Layer 2 で return するため)。発信警告(`handleOutgoing`、`decide()` の外)は Pause と無関係に発火する。
- **対応済み**: `decide()` 関数直前の KDoc に3者の挙動差を明文化し、`CallDecisionTest.high_risk_hour_warning_is_suppressed_while_paused` で現状挙動を回帰テスト化した(意図的な設計ではなく「Layer 2 の早期 return の副作用」であることも明記)。
- **未判断のまま残る**: 高リスク時間帯警告も政府偽装警告と同様に Pause 中に survive させるべきか — これは仕様変更であり要ユーザー判断。変更する場合は上記テストと KDoc を同時に更新すること。

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

---

## 5. 対応推奨順

1. **2-1**(通知の横断デデュープ)— 設計判断込み。ユーザー確認推奨
2. **1-2**(CSV 監査)— 機関ごとの個別判断。ユーザー確認推奨
3. **2-3**(Pause 影響範囲の明文化)— ドキュメント整備のみなら低リスク
4. **1-5 / 2-2** — 製品哲学とのトレードオフ。**必ずユーザーに確認してから着手**
