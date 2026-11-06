# CLAUDE.md

## スキル(myskills由来)

- `.claude/skills/` は実ディレクトリ。[myskills](https://github.com/toshi0907/myskills)(submodule `.myskills`)の各スキルへのsymlinkと、リポジトリ固有のローカルスキルが共存する
- web版のセッション開始時に `.claude/hooks/myskills-skills-sync.sh` が `.myskills` を最新化し、symlinkを同期する(既存は上書きしない)
- 作業前に `.claude/skills/` の各 `SKILL.md` の description を確認し、該当するものを優先的に使う
- フックが「myskills同期による未コミットの差分」を報告したら、該当パスだけを専用コミット(`chore: sync myskills`)にする。`git add -A` / `git commit -a` で作業コミットに混ぜない
