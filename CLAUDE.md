# CLAUDE.md

Android端末上でHTTPサーバーを常駐させ、同一Wi-Fi内のブラウザから写真・動画をアップロードするアプリ。利用者向けの説明は `README.md` 参照。

## スキル(myskills由来)

- `.claude/skills/` は実ディレクトリ。[myskills](https://github.com/toshi0907/myskills)(submodule `.myskills`)の各スキルへのsymlinkと、リポジトリ固有のローカルスキルが共存する
- web版のセッション開始時に `.claude/hooks/myskills-skills-sync.sh` が `.myskills` を最新化し、symlinkを同期する(既存は上書きしない)
- 作業前に `.claude/skills/` の各 `SKILL.md` の description を確認し、該当するものを優先的に使う
- フックが「myskills同期による未コミットの差分」を報告したら、該当パスだけを専用コミット(`chore: sync myskills`)にする。`git add -A` / `git commit -a` で作業コミットに混ぜない

## 構成(`app/src/main/java/com/toshi0907/uploadsrv/`)

- `UploadServer`: Ktor(CIO)。`GET /` で `assets/index.html`、`POST /upload` でmultipartを保存先へストリーミング書き込み
- `MainActivity`(Compose UI)→ `UploadService`(フォアグラウンドサービス)→ `ServerController`(起動/停止をMutexで直列化、状態をStateFlowで公開)→ `UploadServer`
- `AppSettings`: SharedPreferences。保存先はSAFのツリーURI(`DocumentFile` 経由で書き込む)
- `UpdateManager` / `UpdateCheckWorker`: GitHub Releases `latest` を確認してAPKを更新(WorkManagerで定期実行)
- 依存バージョンは `gradle/libs.versions.toml` で管理する

## ビルド・検証

- 開発サンドボックスでは `dl.google.com` に接続できず `./gradlew` が完走しないため、コードを読んで検証し、実ビルドは GitHub Actions(`.github/workflows/android.yml`)に任せる
- ビルド番号 = `GITHUB_RUN_NUMBER`(`versionCode`)。mainへのpushでRelease `latest` を再作成し、本文の `build: <番号>` を `UpdateManager` が解析するため、形式を変える場合は両方を合わせる
- `app/debug.keystore` は上書きインストールのため固定している。差し替えない

## 設計上の決定

- FGS種別は `specialUse`、同名ファイルは上書き、サブフォルダ振り分けなし、画面は日本語のみ、ポートは設定可能(初期値8080、1024〜65535)
