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
| ~~W2~~ | ~~bare NSN の棄権 vs 失敗する2テスト~~ | **✅ 解決(2026-07)**。ITU-T E.164 で棄権が契約上正しいと確定 → **テスト側を修正**(分析全文をテストコメントに移植)。スイートは **285/0 green**、ランナーの ALLOWED リストは空 = 以後いかなる失敗も回帰 |
| ~~W3~~ | ~~宅配・メガバンクがサイレント信頼のまま~~ | **✅ 解決(2026-07)**。A/B/C の3条件基準を確立し**現状配置が正しい**と確認(§1-2)。宅配は攻撃ベクターが SMS で Orange が構造的に観測不能、銀行は代表番号偽装が手口の中核でない |
| ~~W4~~ | ~~かけ直しに事実誤りの発信警告~~ | **✅ 修正済み(2026-07)**。スプーフィングは着信のみ=かけ直すと本物に繋がるため、警察/税務署の `OutboundGuard.record()` を削除(§2-4)。高リスク時間帯は未知番号なので維持 |
| ~~W5~~ | ~~インサイダー脅威への防御なし~~ | **✅ 解決(2026-07)**。USC/NCEA 統計(家族が最頻・被害額3倍)を踏まえ **THREAT_MODEL に対象外の敵対者として明記** + HONESTY_ADDENDUM §14。PIN は**意図的に不採用**(物理アクセスに無力・介護者支援を破壊・セキュリティシアター)|
| W6 | Allow(末尾4桁)と Restore(完全番号)の解除二重化 | §2-2。**判断材料は精密化済み**(ハッシュ保存の増分は小さいが「display-only」の主張強度が下がる/現状は1万分の1を16層迂回で許可)。中間案(桁数追加)は**配送形式非依存性の欠如で撤回済み** — 選択肢は現状維持か完全解の二択。**残る判断は「主張を弱めてよいか」のみ** |
| W7 | STIR/SHAKEN 層は日本キャリアでは休眠(国内未導入)| 仕様として文書化済み。触らない |
| W8 | 警告通知の実効性が未検証(オフラインゆえテレメトリ不可)| **文言は改善済み**(IEEE S&P 2025 の contextual warning で4ロケール改訂)。**「攻撃性」は二択でなく段階**と判明: DND突破・振動は既にON、音は**鳴動中ゆえ意図的にOFFで維持すべき**(KDoc に明文化)。**残る判断はフルスクリーンインテントのみ** = 音量でなく**割り込み**の問題(§1-4)|
| W9 | サンドボックスからタグ push / GitHub Release / APK ビルド不可(三重確認済みの環境制約)| `RELEASING.md` の人間用ランブックが正式な引き継ぎ。**再試行しない** |

## 3. 改善案(優先度順。着手条件を守ること)

### 今すぐ着手可(判断不要・機械的)
1. ~~**ストア層テストの実行拡張**~~ **実装済み(2026-07)**: `run-pure-tests.sh` が最小 Android 型スタブ(heredoc 生成・非コミット)でストア層も実行、計285テスト。脆いテスト3件・設計矛盾テスト1件を修正済み。**次のフロンティア**: WarningNotifier/ManualBlock/FamilyCallback/TrustNotifier/BusinessDirectoryBundle/UI 各種(NotificationManager・NotificationCompat・Context 大量依存)。スタブ面が過大で偽陽性リスクが高いため、これらは実機/SDK 有りの `./gradlew testReleaseUnitTest` に委ねるのが妥当(無理にスタブ化しない)。
2. ~~**ドキュメント数値の定期突き合わせ**~~ → **自動化済み(2026-07)**。`check_comprehensive.sh` の
   **11/12「Doc/data count drift」**が ADR 数・警察ディレクトリ件数・高リスク国番号数・
   マニフェスト項目数をコードから抽出して文書の主張と突き合わせ、**不一致で exit 1**。
   pre-commit / pre-push / CI の3箇所で走る。**手で数えるのをやめること** — この欠陥は
   今セッションだけで5回再発した(47→54、11→12、199→285、7/9→10、8→20)。
   残る手作業は「意味を伴う更新」のみ: ディレクトリや層を変えたら
   README/SPECIFICATION/DEVELOPING/THREAT_MODEL/HONESTY_ADDENDUM/`play_data_safety.json` の
   **記述**を更新し、`ProtectionDataVersion.LAST_UPDATED` は**最古の検証日**を維持。

### ユーザー承認後に着手(製品判断)— 残り2件のみ
3. ~~W2 / W3 / W4 / W5~~ → **✅ すべて解決済み**(§2 の表を参照。W2=E.164 でテスト修正、W3=A/B/C 基準で現状維持が正、W4=コード修正済み、W5=脅威モデル明記)。
4. W6: History にハッシュを持たせ Allow を exact 化するか(= 「display-only」の主張を弱めてよいか)。~~中間案(末尾4桁+桁数)~~は**検討の結果撤回**(桁数は配送形式 domestic/E.164 で変わり、照合が外れると「正当な Allow が効かない」= 誤ブロック継続という最悪方向の失敗になる。§2-2 (5) 参照)。**残る選択肢は現状維持か完全解のみ**。
5. W8: 警告の**フルスクリーンインテント化のみ**(他の段 — DND突破/振動/音 — は決着済み。§1-4)。CHI 2025 が支持、誤警告時の高齢者への負荷が反対根拠。音を足す案は**却下済み**(鳴動中の着信音とノイズ対ノイズになり、文言を読む一瞬を奪う)。**必ず選択肢を提示して人間が決める**。

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
bash tools/run-pure-tests.sh          # 期待値: 285 run / 0 failures(いかなる失敗も回帰)
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

**要約**: 長所は「オフライン・純関数・契約明文化」であり全て守る対象。
2026-07 の監査で **W1/W2/W3/W4/W5 は解決、W6/W8 は判断材料が出揃った状態**まで進んだ。
残る未決は実質2件で、いずれも**技術的事実ではなく製品としての意思**を要する:
W6(display-only の主張を弱めてよいか)/ W8(警告で画面を占有してよいか)。改善は上の優先度と着手条件に従い、
機械的に正当化できるものだけを進め、それ以外は選択肢を添えて人間に返すこと。
