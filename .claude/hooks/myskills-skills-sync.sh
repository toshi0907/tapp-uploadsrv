#!/bin/bash
# Claude Code on the web のセッション開始時に、myskills submodule
# (.myskills) を最新のリモート内容へ更新し、`.claude/skills/` 配下の
# myskills由来スキルsymlinkを同期するフック。
#
# 前提となるリポジトリ構成(対象リポジトリ側で事前に一度だけ設定):
#   .myskills/          ... myskills を submodule として追加した場所
#   .claude/skills/      ... 実ディレクトリ。myskills由来のスキルは
#                            スキルごとの symlink として個別に登録する
#                            (`.claude/skills` 自体をsymlinkにはしない)
#
# 同期内容:
#   - `.claude/skills/<skill名>` がまだ存在しない場合のみ symlink を作成する。
#     対象リポジトリ固有のローカルスキルなど、既に同名のファイル/ディレクトリが
#     存在する場合は上書きしない。
#   - `../../.myskills/claude-skills/` を指すリンク切れsymlink(myskills側で
#     名前変更・削除されたスキル)は削除する。それ以外のsymlinkには触れない。
#   - 上記と submodule 更新で生じた差分(myskills同期の差分)は自動コミットせず、
#     一覧を表示する。作業内容とは別のコミットとしてコミットする運用とする。
#
# 設置方法(対象リポジトリ側):
#   1. このファイルを .claude/hooks/myskills-skills-sync.sh にコピー
#   2. chmod +x .claude/hooks/myskills-skills-sync.sh
#   3. .claude/settings.json の hooks.SessionStart に、matcher "startup|resume" で
#      このスクリプトを登録する(記述例: myskills リポジトリの
#      scripts/session-start-hook/settings.snippet.json)
set -euo pipefail

# CLAUDE_PROJECT_DIR 未設定(ローカルでの手動実行)時は、対象リポジトリのルートで動作する
# (.myskills 内から実行された場合も親リポジトリを使う)。
root="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$root" ]; then
  root="$(git rev-parse --show-superproject-working-tree)"
  [ -n "$root" ] || root="$(git rev-parse --show-toplevel)"
fi
cd "$root"

log() {
  echo "myskills-skills-sync: $*" >&2
}

if [ ! -f .gitmodules ] || ! grep -q '\.myskills' .gitmodules 2>/dev/null; then
  log ".myskills submodule が見つかりません。スキップします。"
  exit 0
fi

# submoduleの更新はClaude Code on the web(使い捨て環境)でのみ自動実行する。
# ローカル(永続環境)は明示的な `git submodule update --init --remote` を想定。
# 更新に失敗しても(ネットワーク/プロキシ等)、既存の内容でsymlink同期は続行する。
if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  # --remote: myskills側の最新コミットを取得する
  # --init:   初回clone直後でsubmoduleが未初期化でも動くようにする
  if git submodule update --init --remote -- .myskills >&2; then
    log "submodule synced to $(git -C .myskills rev-parse --short HEAD)"
  else
    log "警告: submodule の更新に失敗しました。既存の内容のまま続行します。"
  fi
fi

skills_src=".myskills/claude-skills"
link_prefix="../../.myskills/claude-skills/"

# myskills由来のsymlink(リンク先が link_prefix で始まる)かどうか
is_myskills_link() {
  [ -L "$1" ] || return 1
  case "$(readlink "$1")" in
    "$link_prefix"*) return 0 ;;
    *) return 1 ;;
  esac
}

# submoduleが未初期化(更新失敗など)の場合、全symlinkがリンク切れに見えて
# 誤って削除してしまうため、同期自体を行わない。
if [ ! -d "$skills_src" ]; then
  log "警告: $skills_src が存在しないため、スキルsymlinkの同期をスキップします。"
  exit 0
fi

mkdir -p .claude/skills

added=()
removed=()

# myskills側で名前変更・削除されたスキルのリンク切れsymlinkを削除する。
# myskills由来(リンク先が link_prefix で始まる)と判別できるものに限る。
for link in .claude/skills/*; do
  is_myskills_link "$link" || continue
  [ -e "$link" ] && continue
  rm -- "$link"
  removed+=("$link")
done

for skill_dir in "$skills_src"/*/; do
  [ -d "$skill_dir" ] || continue
  name="$(basename "$skill_dir")"
  target=".claude/skills/$name"

  # 同名のファイル/ディレクトリ/symlink(壊れているものも含む)が
  # 既に存在する場合は上書きしない。
  if [ -e "$target" ] || [ -L "$target" ]; then
    continue
  fi

  ln -s "${link_prefix}${name}" "$target"
  added+=("$target")
done

[ "${#added[@]}" -gt 0 ] && log "${#added[@]}件のスキルsymlinkを追加しました: ${added[*]}"
[ "${#removed[@]}" -gt 0 ] && log "${#removed[@]}件のリンク切れsymlinkを削除しました: ${removed[*]}"

# myskills同期による未コミットの差分(ステージ済みも含む。以前のセッションで
# 生じたままのものも対象)を一覧表示する。自動コミットはしない。
# SessionStart hook の stdout はClaudeのコンテキストに渡るため、stdoutに出力する。
pending=()
while IFS= read -r -d '' entry; do
  path="${entry:3}"
  case "$path" in
    .myskills) pending+=("$path") ;;
    .claude/skills/*)
      if is_myskills_link "$path"; then
        pending+=("$path")
      elif [ ! -e "$path" ] && [ ! -L "$path" ]; then
        # 削除済み: コミット済みの内容(symlinkのリンク先)で判定する
        case "$(git cat-file -p "HEAD:$path" 2>/dev/null)" in
          "$link_prefix"*) pending+=("$path") ;;
        esac
      fi
      ;;
  esac
done < <(git status --porcelain -z --no-renames --ignore-submodules=dirty \
           -- .myskills .claude/skills)

if [ "${#pending[@]}" -gt 0 ]; then
  echo "myskills-skills-sync: myskills同期による未コミットの差分があります: ${pending[*]}"
  echo "作業内容とは混ぜず、上記パスだけを専用のコミット(例: chore: sync myskills)としてコミットしてください。"
fi

exit 0
