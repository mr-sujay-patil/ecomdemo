# 👤 Your Role (the User)

**Claude Code is the engineer. You are the tech lead and the learner.** Claude Code writes, tests, and documents. You set up the environment, review, decide, merge, and make sure you understand each phase before the next one starts.

---

## 1. Before development (one-time setup)

**Tools**
- [ ] Git, the GitHub CLI (`gh`), JDK 21, Docker Desktop, your IDE, and Claude Code installed
- [ ] A database client (DBeaver or pgAdmin)

**Accounts**
- [ ] `gh auth login` done by you
- [ ] Decide whether the repository is public or private (public = SonarQube Cloud free and a portfolio piece)

**Machine**
- [ ] 16 GB or more of RAM recommended for later phases (Kafka, PostgreSQL, Redis, Grafana, local Kubernetes)

**Workspace**
- [ ] Create the `ecomdemo` folder and copy this package into it (`CLAUDE.md` + `docs/`)
- [ ] Open Claude Code in that folder

**Permissions**
- [ ] Approve routine commands as you get comfortable (`./mvnw`, `git add/commit`, `docker compose`)
- [ ] Don't skip permissions entirely. Keeping Git pushes and merges visible is part of how your rules stay enforced.

## 2. Kickoff (once)

1. Paste the kickoff prompt from `docs/process/kickoff-and-commands.md`.
2. Answer "public or private".
3. When Claude Code stops for GitHub settings, apply them in the UI (branch protection, no automatic branch deletion, merge commits only), then reply `done`.

## 3. Every phase: your loop

| # | Your action | Details |
|---|---|---|
| 1 | **Let it work** | Approve permission prompts and answer questions. Don't edit files on its branch; if you must, tell it. |
| 2 | **Read the Phase Review Report** | Posted when it stops at the PR |
| 3 | **Review the PR on GitHub** | Start with the 3–5 areas it flags. Check the test report and every ⚠️ item |
| 4 | **Try it yourself** | `git fetch && git checkout <branch>`, run the app and `scripts/smoke-test.sh` |
| 5 | **Learn** | Go through "Concepts to understand". Ask things like "explain why X was used here". Gate: *could I explain this phase in an interview without notes?* |
| 6 | **Decide** | Something to fix → `changes: <feedback>`. Good → merge on GitHub with **"Create a merge commit"** |
| 7 | **Continue** | `/clear` → `merged, continue` |

Plan for 30–90 minutes of review and learning per phase.

## 4. Phases that need your hands

| Phase | You do |
|---|---|
| 0 | Apply the GitHub repository settings in the UI |
| 7+ | Keep Docker Desktop running |
| 11 | Add "Require status checks to pass" to branch protection. Merge only when CI is green from then on |
| 12 | (SonarQube Cloud) Create the account and set `SONAR_TOKEN` |
| 25 | Install kind or minikube and kubectl; give Docker more resources |
| 26 | Azure subscription, `az login`, approve costs, confirm the teardown |
| 27–29 | Set the LLM API key as an environment variable, or install Ollama |

**Secrets rule:** you set every secret yourself (environment variable or GitHub secret). Never paste one into the chat.

## 5. Interruptions

| Situation | What to do |
|---|---|
| The session closed, crashed, or hit a usage limit | Open Claude Code → `continue`. It resumes from `docs/progress/CURRENT.md` |
| Not sure where things are | `status` |
| You need to leave mid-phase | `stop` (it saves a checkpoint), then later `continue` |
| It breaks a Git rule | `stop` immediately and describe what happened. Fix it together |
| It's stuck (failing test, version conflict) | It stops and reports. You choose the direction |

## 6. Never do

- Commit to `main`
- Squash- or rebase-merge
- Delete feature branches
- Merge a PR you haven't reviewed
- Paste secrets into chat
- Approve a command you don't understand without asking
- Skip ahead to a later phase

## 7. After the whole journey

- [ ] Tag a `v1.0` release on `main`
- [ ] Ask Claude Code for a final architecture walkthrough document (through a normal feature branch and PR)
- [ ] Tear down Azure resources, local containers, and clusters
- [ ] Write your own retrospective: what each phase taught you and what you'd do differently
- [ ] Polish the README as a showcase of the journey

## What Claude Code expects from you

- A ready environment
- Timely decisions using the command vocabulary
- **You** doing the merges
- **You** setting the secrets
- No parallel edits on its branch
- Honest feedback when something looks wrong
