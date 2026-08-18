# Orange — Opus / Sonnet 向け作業指示書 (Model Playbook)

作成: 2026-07 / 対象: 将来の Claude セッション(Opus・Sonnet・その他)

前提コンテキストゼロで読めるように書かれている。**作業開始前に必ずこのファイルと
`docs/FEATURE_AUDIT.md` を読むこと。** 本書は「何を・どう・どのモデルで」、
FEATURE_AUDIT は「何が未解決か」を担当する。

---

## 0. 絶対制約(モデル問わず。違反 = 即 revert 対象)

1. `INTERNET` / `READ_CONTACTS` / `READ_CALL_LOG` 権限の追加禁止。製品のプライバシー上の核心的約束。
2. 開発・push は `claude/sleepy-hypatia-o9gwuv` ブランチのみ(git プロキシも他 ref を 403 で拒否する)。
3. テストは JVM 純粋ユニットテストのみ。**Robolectric 禁止**。Context 依存コードは純関数を分離してテストする。
4. APK ≤ 1 MiB(CI ゲート `tools/check_apk_size.sh`)。
5. `PhoneNumbers.normalize()` / `phoneVariants()` / `isCacheableSilence()` は single source of truth。再実装禁止。
6. 番号照合・キャッシュ書込は**必ず全 `phoneVariants()` に対して行う**(コードベース全体の規約)。
7. ロケール文字列を1つでも触ったら、en/ja/zh/ko の4ファイルでキー集合一致を検証する
   (`grep -o 'name="[a-z_0-9]*"' | sort -u` の diff がゼロ、XML パース可)。

## 1. プロダクトの長所(壊さないこと。これが製品価値そのもの)

- **完全オフライン設計**: ネットワークコードゼロ。詐欺対策アプリとして唯一級の差別化。CI が守っている(`check_no_network.sh`)。
- **16層 first-match-wins の純関数エンジン** (`CallDecision.kt` `decide()`): Android 型・Context・時計読み取りなし。テスト容易性の源泉。
- **明示的な契約のコメント文化**: 「Pause means every call rings」等、KDoc が仕様書として機能する。今セッションの実バグ3件は全て「KDoc の契約 vs 実装の矛盾」から発見された。
- **warn-but-ring レーン** (Layer 9/9b): 警察・税務署は絶対にブロックせず警告付きで鳴らす。ADR 011 の一般原則(RING 上書き層は SILENCE 層より前)。
- **PII 最小化**: 番号は salted SHA-256(SaltVault が Keystore 暗号化)、履歴はマスク表示のみ、backup 除外。
- **逆行クロック防御・エントリ上限**: 全ストア(OutboundGuard/WangiriTracker/RepeatCaller/RateLimiter)で一貫。
- **2026 フィールドデータによる外部検証済み**: 詐欺番号の75.5%が国際 → 「未知の国際着信は静音」設計と一致。都内ニセ警察詐欺38.8%減とアプリ利用増の相関(警視庁)。`RESEARCH_BASIS.md` 参照。

## 2. 短所・弱点(既知。勝手に「直さない」こと — 多くは製品判断待ち)

| # | 弱点 | 状態 |
|---|------|------|
| ~~W1~~ | ~~テストが CI で一度も実行されていなかった~~ | **✅ 解決(2026-07)**。CI を作成(除外は初回コミットからの事故と判明、§1-6)。**`docs/ci/ci.yml` に待機中** — App トークンに `workflows` 権限が無いため、人間が `git mv` して push すると有効。static / unit-tests(285) / android-build の3ジョブ。`.githooks/` は**オプトイン**なので CI が迂回不能な版。WarningNotifier/UI 等 Context 大量依存は `android-build` ジョブの `testReleaseUnitTest` がカバー |
| W2 | `DomesticSpoofDetector.toDomestic()` が先頭0/+81 以外を棄権 → 短縮番号・先頭ゼロ欠落の判定が到達不能。**テスト2件が実行すると失敗する**(意図的に残してある可視シグナル)| FEATURE_AUDIT §1-7。設計判断待ち |
| W3 | business_directory.csv の宅配・メガバンク等がサイレント信頼のまま(偽装頻度高いが正当着信も多い)| FEATURE_AUDIT §1-2。機関別判断待ち |
| W4 | 警察/税務署番号へのかけ直し(推奨される安全行動)に「ブロックした番号」という事実誤りの発信警告が出る | FEATURE_AUDIT §2-4。文言/挙動判断待ち |
| W5 | インサイダー脅威(ロック解除済み端末を持つ家族・介護者)への防御なし。PIN は「設定を増やさない」哲学と衝突 | FEATURE_AUDIT §1-5。判断待ち |
| W6 | Allow(末尾4桁)と Restore(完全番号)の解除二重化 | FEATURE_AUDIT §2-2。プライバシー設計とのトレードオフ |
| W7 | STIR/SHAKEN 層は日本キャリアでは休眠(国内未導入)| 仕様として文書化済み。触らない |
| W8 | 警告通知の実効性が未検証(オフラインゆえテレメトリ不可)| CHI 2025 論文がフルスクリーン化を支持するが UX 判断待ち(§1-4)|
| W9 | サンドボックスからタグ push / GitHub Release / APK ビルド不可(三重確認済みの環境制約)| `RELEASING.md` の人間用ランブックが正式な引き継ぎ。**再試行しない** |

## 3. 改善案(優先度順。着手条件を守ること)

### 今すぐ着手可(判断不要・機械的)
1. ~~**ストア層テストの実行拡張**~~ **実装済み(2026-07)**: `run-pure-tests.sh` が最小 Android 型スタブ(heredoc 生成・非コミット)でストア層も実行、計285テスト。脆いテスト3件・設計矛盾テスト1件を修正済み。**次のフロンティア**: WarningNotifier/ManualBlock/FamilyCallback/TrustNotifier/BusinessDirectoryBundle/UI 各種(NotificationManager・NotificationCompat・Context 大量依存)。スタブ面が過大で偽陽性リスクが高いため、これらは実機/SDK 有りの `./gradlew testReleaseUnitTest` に委ねるのが妥当(無理にスタブ化しない)。
2. **ドキュメント数値の定期突き合わせ**: このセッションで大量に発見・修正したパターン(件数・層数・閾値のドリフト)。ディレクトリや層を変更したら README/SPECIFICATION/DEVELOPING/THREAT_MODEL/HONESTY_ADDENDUM/`play_data_safety.json` を同時更新し、`ProtectionDataVersion.LAST_UPDATED` は**最古の検証日**を維持。

### ユーザー承認後に着手(製品判断)
3. W2: `toDomestic()` の棄権挙動(numbering-plan 厳格性)。
4. W4: warn-but-ring 由来の発信警告に専用文言(3案は §2-4 に記載済み)。
5. W3: 宅配/銀行の warn レーン移行(機関ごと)。
6. W5/W6/W8: 哲学トレードオフ級。**必ず選択肢を提示して人間が決める**。

### 人間の環境が必要
7. W9 の3ステップ(`RELEASING.md` 手順、約2分)。
8. ~~`.github/workflows/` 復活~~ → **✅ 実施済み**(`ci.yml`)。以後は CI が壊れていないかを確認するだけでよい。
9. `play_data_safety.json` の `privacy_policy_url`: **URL は確定済み**(`https://shizukutanaka.github.io/Orange/privacy_policy.html`)。残るのは Settings → Pages → `main` / `/docs` を有効化することと、`docs/privacy_policy.html` を `main` にマージすること。判断は不要、操作のみ。

## 4. モデル使い分け(このリポジトリでの実績ベース)

| タスク | 推奨 | 根拠(実績) |
|---|---|---|
| ロケール追随・定数抽出・コメント修正 | **Haiku/Sonnet** | 機械的。プレースホルダ `%1$s` と `\'` エスケープ、4ロケール一致検証だけ守れば安全 |
| 既存パターンの横展開(新 warn ディレクトリ等)| **Sonnet** | お手本あり(TaxAgencyDirectory = Police の複製 + Layer 順 + 回帰テスト `warnDirectories` 追加)|
| 単発バグ修正・テスト追加・doc 同期 | **Sonnet** | このブランチの大半のコミットがこの水準 |
| `CallDecision.kt` / `SilentBlockerService.kt` の変更 | **Opus 以上** | 層順ミス=本物の警察を無音ブロック級の実害。Pause 契約バグ(RepeatCaller が decide() の外で SILENCE)はこの2ファイルの結合部で起きた |
| 契約 vs 実装の監査(ソクラテス式再読)| **Opus/Fable** | 実バグ発見は全てこの手法: KDoc の主張を疑って実装と突き合わせる。「動くか」でなく「主張どおりか」を問う |
| 製品判断の叩き台(W3-W6)| **Opus/Fable + 人間** | 選択肢と結果の言語化。決定は人間 |

### Opus/Sonnet 共通の作業手順(このセッションで有効だった規律)
1. 開始時に `docs/FEATURE_AUDIT.md` を読む(再発見の無駄と制約違反を防ぐ)。
2. 変更は小さく、**1論点=1コミット**。コミットメッセージに「何が矛盾していたか」を書く(将来の再発見防止)。
3. 検証: Gradle ビルドはサンドボックスで不可 → `bash tools/run-pure-tests.sh`(285テスト、SDK不要)+ 机上トレース。**push 後は CI(`ci.yml`)が全ゲート + SDK 有りの完全ビルドを回す**ので、サンドボックスで検証できなかった部分はそこで確認できる。
4. 発見した問題は3分類 — **機械的に直せる**(直す)/**陳腐化**(記録どおり更新)/**設計判断**(FEATURE_AUDIT に記録して止まる)。テストの assertion を黙って実装に合わせる改変は禁止(gap の隠蔽)。
5. 迷ったら削る方向へ(DESIGN_NOTES の subtraction 哲学)。機能追加 PR より削除 PR が歓迎される製品。

## 5. 危険ファイル一覧(変更時は Opus 以上 + 追加注意)

- `CallDecision.kt` — 層順序が生命線。変更したら `DecisionPriorityTest`/`EngineInvariantTest` を実行。Pause の適用範囲を変えるなら decide() 冒頭の KDoc も更新(3経路の整合)。
- `SilentBlockerService.kt` — decide() の**外側**にある判定(AllowSuffix 早期 RING、trusted-set、RepeatCaller ゲート)は Pause 契約を自前で守る必要がある(過去バグの温床)。
- `ManualBlock.kt` — 3点セット(record/MANUAL_BLOCK 除外集計/classify)のどれか1つだけ変えると壊れる(FEATURE_AUDIT §3)。
- `business_directory.csv` — 警察・税務署番号を**絶対に追加しない**(サイレント信頼になる)。回帰テスト `shipped_csv_never_bundles_a_warn_directory_number` が守る。新 warn ディレクトリを作ったらこのテストの `warnDirectories` に追加。
- `EmergencyWhitelist.kt` — 削除方向の変更禁止。「A silenced 110 is a killed user」。

## 6. 検証コマンド早見表

```bash
# 純粋ロジック層のテスト実行(SDK 不要、~0.2秒)
bash tools/run-pure-tests.sh          # 期待値: 285 run / 2 failures(W2 の意図的シグナル)
# CI と同じ全ゲートをローカルで:
bash .githooks/pre-push               # privacy guard → oracle 31 → 285 tests

# ロケール4ファイルのキー集合一致
cd app/src/main/res && for f in values values-ja values-zh values-ko; do \
  grep -o 'name="[a-z_0-9]*"' $f/strings.xml | sort -u > /tmp/k_$f; done && \
  diff /tmp/k_values /tmp/k_values-zh && diff /tmp/k_values /tmp/k_values-ko && \
  diff /tmp/k_values /tmp/k_values-ja && echo OK

# ネットワークコード混入チェック / JSON 妥当性
bash tools/check_no_network.sh app/src/main
python3 -m json.tool docs/play_data_safety.json > /dev/null && echo valid
```

---

**要約**: 長所は「オフライン・純関数・契約明文化」であり全て守る対象。短所の大半は
「わかっていて、判断を待っている」状態(W2-W8)。改善は上の優先度と着手条件に従い、
機械的に正当化できるものだけを進め、それ以外は選択肢を添えて人間に返すこと。
