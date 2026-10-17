#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage: create-release-branch.sh --source COMMIT --release X.Y.Z [options]

Create a Keycloak Benchmark release branch from a commit in main.

Required arguments:
  --source COMMIT              Full commit SHA from main
  --release X.Y.Z              Benchmark release version

Options:
  --publish                    Commit and push the release branch
  --remote REMOTE              Git remote to use (default: upstream)
  --main-branch BRANCH         Main branch name (default: main)
  --keycloak-version X.Y.Z     Override automatic server version selection
  --keycloak-client-version V  Override automatic client version selection
  --help                       Show this help

Without --publish, the script validates the generated tree and prints its diff.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

source_commit=
release_version=
keycloak_version=
keycloak_client_version=
remote=upstream
main_branch=main
publish=false

while (($#)); do
  case "$1" in
    --source)
      source_commit=${2-}
      shift 2
      ;;
    --release)
      release_version=${2-}
      shift 2
      ;;
    --keycloak-version)
      keycloak_version=${2-}
      shift 2
      ;;
    --keycloak-client-version)
      keycloak_client_version=${2-}
      shift 2
      ;;
    --remote)
      remote=${2-}
      shift 2
      ;;
    --main-branch)
      main_branch=${2-}
      shift 2
      ;;
    --publish)
      publish=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$source_commit" ]] || die "--source is required"
[[ -n "$release_version" ]] || die "--release is required"
[[ "$source_commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "--source must be a full 40-character commit SHA"
[[ "$release_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "--release must have the form X.Y.Z"

release_major=${BASH_REMATCH[1]}
release_minor=${BASH_REMATCH[2]}
release_line="${release_major}.${release_minor}"
branch_name="${release_line}-release"
project_version="${release_version}-SNAPSHOT"

require_command git
require_command gh
require_command jq
require_command curl
require_command sort
require_command perl

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || die "Run this script from a Git repository"
cd "$repo_root"

git remote get-url "$remote" >/dev/null 2>&1 || die "Git remote '$remote' does not exist"
gh auth status --hostname github.com >/dev/null 2>&1 || die "gh is not authenticated with github.com"

echo "Fetching $remote/$main_branch..."
git fetch --quiet "$remote" "$main_branch"

source_commit=$(git rev-parse --verify "${source_commit}^{commit}") || die "Source commit does not exist"
[[ $(git rev-parse "$source_commit") == "$source_commit" ]] || die "Source commit did not resolve exactly"
git merge-base --is-ancestor "$source_commit" "$remote/$main_branch" || \
  die "Source commit is not an ancestor of $remote/$main_branch"

git show-ref --verify --quiet "refs/heads/$branch_name" && die "Local branch already exists: $branch_name"

remote_ref_exists() {
  local ref=$1 status
  git ls-remote --exit-code "$remote" "$ref" >/dev/null 2>&1 && return 0
  status=$?
  [[ $status -eq 2 ]] && return 1
  die "Could not check remote ref $ref"
}

remote_ref_exists "refs/heads/$branch_name" && die "Remote branch already exists: $branch_name"

resolve_keycloak_version() {
  local releases candidate
  releases=$(gh api --paginate repos/keycloak/keycloak/releases | jq -s 'add')
  candidate=$(
    jq -r --arg prefix "${release_line}." '
      .[]
      | select(.draft == false and .prerelease == false)
      | .tag_name
      | select(startswith($prefix))
      | select(test("^[0-9]+\\.[0-9]+\\.[0-9]+$"))
    ' <<<"$releases" | sort -V | tail -n1
  )
  [[ -n "$candidate" ]] || die "No stable Keycloak $release_line.x GitHub release found"
  jq -e --arg tag "$candidate" --arg asset "keycloak-${candidate}.zip" '
    .[] | select(.tag_name == $tag) | .assets[] | select(.name == $asset)
  ' <<<"$releases" >/dev/null || die "Keycloak release $candidate has no keycloak-${candidate}.zip asset"
  printf '%s' "$candidate"
}

resolve_keycloak_client_version() {
  local metadata candidate
  metadata=$(curl --fail --silent --show-error \
    https://repo.maven.apache.org/maven2/org/keycloak/keycloak-admin-client/maven-metadata.xml)
  candidate=$(
    sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' <<<"$metadata" \
      | awk -v major="$release_major" '$0 ~ "^" major "\\.[0-9]+\\.[0-9]+$"' \
      | sort -V \
      | tail -n1
  )
  [[ -n "$candidate" ]] || die "No stable Keycloak admin client release found for major $release_major"
  curl --fail --silent --show-error --head \
    "https://repo.maven.apache.org/maven2/org/keycloak/keycloak-admin-client/$candidate/keycloak-admin-client-$candidate.pom" \
    >/dev/null || die "Keycloak admin client $candidate could not be downloaded"
  printf '%s' "$candidate"
}

if [[ -z "$keycloak_version" ]]; then
  echo "Resolving latest Keycloak $release_line.x server release..."
  keycloak_version=$(resolve_keycloak_version)
fi
[[ "$keycloak_version" =~ ^${release_line//./\.}\.[0-9]+$ ]] || \
  die "Keycloak version must be a stable $release_line.x version"
gh api "repos/keycloak/keycloak-k8s-resources/git/ref/tags/$keycloak_version" >/dev/null || \
  die "Keycloak Kubernetes resources tag is missing: $keycloak_version"

if [[ -z "$keycloak_client_version" ]]; then
  echo "Resolving latest Keycloak $release_major.x client release..."
  keycloak_client_version=$(resolve_keycloak_client_version)
fi
[[ "$keycloak_client_version" =~ ^${release_major}\.[0-9]+\.[0-9]+$ ]] || \
  die "Keycloak client version must be a stable $release_major.x version"

worktree=$(mktemp -d "${TMPDIR:-/tmp}/keycloak-benchmark-release.XXXXXX")
cleanup() {
  git -C "$repo_root" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  git -C "$repo_root" branch --delete --force "$branch_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

git worktree add --quiet --detach "$worktree" "$source_commit"
cd "$worktree"
git switch --quiet -c "$branch_name"

replace_literal() {
  local file=$1 old=$2 new=$3 required=${4:-true}
  [[ -f "$file" ]] || {
    [[ "$required" == false ]] && return
    die "Expected file is missing at the selected commit: $file"
  }
  if ! grep -Fq -- "$old" "$file"; then
    [[ "$required" == false ]] && return
    die "Expected value '$old' is missing from $file"
  fi
  OLD_VALUE=$old NEW_VALUE=$new perl -pi -e 's/\Q$ENV{OLD_VALUE}\E/$ENV{NEW_VALUE}/g' "$file"
}

# Benchmark project and documentation versions.
for file in \
  pom.xml \
  benchmark/pom.xml \
  dataset/pom.xml \
  provision/rosa-cross-dc/keycloak-benchmark-crossdc-tests/pom.xml; do
  replace_literal "$file" 999.0.0-SNAPSHOT "$project_version"
done
for file in doc/benchmark/antora.yml doc/dataset/antora.yml doc/kubernetes/antora.yml; do
  replace_literal "$file" 999.0.0 "$release_version"
done
for file in \
  ansible/env_example.yml \
  ansible/env_rosa_benchmark.yml \
  doc/benchmark/modules/ROOT/pages/run/running-benchmark-ansible.adoc; do
  replace_literal "$file" keycloak-benchmark-999.0.0-SNAPSHOT "keycloak-benchmark-$project_version"
done

# Server and independently released Java client versions.
replace_literal pom.xml \
  "<keycloak.version>$project_version</keycloak.version>" \
  "<keycloak.version>$keycloak_version</keycloak.version>"
replace_literal pom.xml \
  "<keycloak-client.version>$project_version</keycloak-client.version>" \
  "<keycloak-client.version>$keycloak_client_version</keycloak-client.version>"

replace_literal provision/common/Taskfile.yaml \
  "KC_OPERATOR_TAG: '{{default \"nightly\" .KC_OPERATOR_TAG}}'" \
  "KC_OPERATOR_TAG: '{{default \"$keycloak_version\" .KC_OPERATOR_TAG}}'"
replace_literal provision/keycloak-tasks/Taskfile.yaml \
  "KC_OPERATOR_TAG: '{{default \"nightly\" .KC_OPERATOR_TAG}}'" \
  "KC_OPERATOR_TAG: '{{default \"$keycloak_version\" .KC_OPERATOR_TAG}}'"
replace_literal provision/common/Taskfile.yaml \
  "releases/download/nightly/keycloak-999.0.0-SNAPSHOT.zip" \
  "releases/download/$keycloak_version/keycloak-$keycloak_version.zip"
replace_literal provision/common/Taskfile.yaml \
  keycloak-cli/keycloak-999.0.0-SNAPSHOT \
  "keycloak-cli/keycloak-$keycloak_version"
replace_literal provision/keycloak-tasks/keycloak-image-helm/values.yaml \
  quay.io/keycloak/keycloak:nightly \
  "quay.io/keycloak/keycloak:$keycloak_version"

# Release-branch documentation and snapshot release metadata.
replace_literal README.adoc \
  'This `main` branch Keycloak benchmark works with the Keycloak nightly versions,' \
  "This \`$branch_name\` branch of Keycloak Benchmark works with Keycloak $keycloak_version,"
replace_literal doc/ROOT/modules/ROOT/pages/index.adoc \
  'The `main` branch binaries of Keycloak benchmark work with the Keycloak nightly versions,' \
  "The \`$branch_name\` branch binaries of Keycloak Benchmark work with Keycloak $keycloak_version,"
replace_literal doc/kubernetes/modules/ROOT/partials/default-deployment.adoc \
  'the latest nightly images of the Keycloak Operator and Keycloak' \
  "Keycloak Operator and Keycloak images version $keycloak_version"
replace_literal doc/kubernetes/modules/ROOT/pages/customizing-deployment.adoc \
  'Default value: `nightly`' \
  "Default value: \`$keycloak_version\`"
replace_literal doc/kubernetes/modules/ROOT/pages/customizing-deployment.adoc \
  'When using a nightly image of the Keycloak operator, this defaults to `quay.io/keycloak/keycloak:nightly`.' \
  "With Keycloak Operator $keycloak_version, this defaults to \`quay.io/keycloak/keycloak:$keycloak_version\`."
replace_literal doc/kubernetes/modules/ROOT/pages/openshift/installation-openshift.adoc \
  refs/tags/nightly/kubernetes/kubernetes.yml \
  "refs/tags/$keycloak_version/kubernetes/kubernetes.yml"
replace_literal doc/kubernetes/modules/ROOT/pages/openshift/cross-site-rosa.adoc \
  'This setup, by default, deploys Keycloak from nightly build.' \
  "This setup, by default, deploys Keycloak $keycloak_version."
replace_literal .github/workflows/snapshot.yml \
  '--notes '\''Latest version built from the `${{ github.ref_name }}` branch.'\''' \
  '--notes '\''Latest version built from the `${{ github.ref_name }}` branch for Keycloak '"$keycloak_version"'.'\'''

# Examples that describe building Keycloak itself use the server version, not
# the Keycloak Benchmark project version.
replace_literal doc/kubernetes/modules/ROOT/pages/util/custom-image-for-keycloak.adoc \
  keycloak-999.0.0-SNAPSHOT \
  "keycloak-$keycloak_version" false

git diff --check

remaining_project=$(git grep -n '999\.0\.0-SNAPSHOT' -- ':!doc/benchmark/modules/ROOT/pages/release.adoc' || true)
[[ -z "$remaining_project" ]] || die "Unconverted development versions remain:\n$remaining_project"

[[ $(git diff -- pom.xml | grep -c "+.*<keycloak.version>$keycloak_version</keycloak.version>") -eq 1 ]] || \
  die "Keycloak server version was not updated exactly once"
[[ $(git diff -- pom.xml | grep -c "+.*<keycloak-client.version>$keycloak_client_version</keycloak-client.version>") -eq 1 ]] || \
  die "Keycloak client version was not updated exactly once"

echo
echo "Release branch prepared:"
echo "  Source commit:           $source_commit"
echo "  Release branch:          $branch_name"
echo "  Benchmark version:       $project_version"
echo "  Keycloak server version: $keycloak_version"
echo "  Keycloak client version: $keycloak_client_version"
echo
git diff --stat

echo
echo "Building the generated release branch..."
./mvnw -B clean verify
echo "Release branch build succeeded."

if [[ "$publish" == false ]]; then
  echo
  git diff
  echo
  echo "Dry run complete; no branch was published."
  exit 0
fi

git add --all
git commit --signoff --message "Prepare $release_line release branch" \
  --message "Keycloak server: $keycloak_version
Keycloak client: $keycloak_client_version
Source commit: $source_commit"
git push "$remote" "HEAD:refs/heads/$branch_name"

echo "Published branch $branch_name to $remote."
echo "The Snapshot Release workflow will create the $project_version tag and artifacts."
