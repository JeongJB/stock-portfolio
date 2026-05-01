# GitHub Actions OIDC IAM 자산

GitHub Actions 워크플로(`.github/workflows/backend-deploy.yml`, `frontend-deploy.yml`)가 AWS 자격증명을 받기 위해 사용하는 IAM Role 정의 자산.

- `trust-policy.json` — Role 의 신뢰 정책. 이 저장소(`JeongJB/stock-portfolio`)의 `master` 브랜치에서만 assume 가능.
- `gha-deploy-policy.json` — Role 의 권한 정책. SAM 스택 + 프론트엔드 S3/CloudFront 배포에 필요한 최소 권한.

두 파일 모두 평문이라 시크릿이 아님. 회전·재현 편의를 위해 리포에 박제.

## 사전 준비

GitHub OIDC provider 가 IAM 에 등록돼 있어야 한다 (사용자 환경은 등록 완료).
없을 경우:

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

## Role 생성·갱신 절차 (1회)

`<ACCOUNT_ID>` 를 실제 AWS 계정 번호로 치환한 임시 파일을 만든 뒤 적용한다.

```bash
ACCOUNT_ID=123456789012
sed "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" infra/iam/trust-policy.json > /tmp/trust.json
sed "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" infra/iam/gha-deploy-policy.json > /tmp/policy.json

aws iam create-role \
  --role-name stock-portfolio-gha-deploy \
  --assume-role-policy-document file:///tmp/trust.json

aws iam put-role-policy \
  --role-name stock-portfolio-gha-deploy \
  --policy-name stock-portfolio-gha-deploy \
  --policy-document file:///tmp/policy.json
```

GitHub Secret 등록:

```bash
gh secret set AWS_ROLE_ARN \
  --repo JeongJB/stock-portfolio \
  --body "arn:aws:iam::$ACCOUNT_ID:role/stock-portfolio-gha-deploy"
```

권한 정책 갱신 시:

```bash
sed "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" infra/iam/gha-deploy-policy.json > /tmp/policy.json
aws iam put-role-policy \
  --role-name stock-portfolio-gha-deploy \
  --policy-name stock-portfolio-gha-deploy \
  --policy-document file:///tmp/policy.json
```

신뢰 정책 갱신 시:

```bash
sed "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" infra/iam/trust-policy.json > /tmp/trust.json
aws iam update-assume-role-policy \
  --role-name stock-portfolio-gha-deploy \
  --policy-document file:///tmp/trust.json
```

## sub claim 의미

신뢰 정책의 `token.actions.githubusercontent.com:sub` 값이 `repo:JeongJB/stock-portfolio:ref:refs/heads/master` 인 이유:

- `repo:JeongJB/stock-portfolio` — 정확히 이 저장소만.
- `ref:refs/heads/master` — `master` 브랜치 기준의 워크플로 실행만.

PR 워크플로(`pull_request` 트리거)는 sub claim 이 `repo:JeongJB/stock-portfolio:pull_request` 가 되어 이 Role 을 assume 할 수 없다 — PR 에서 cloud 자원에 손대지 못하게 하는 것이 의도. PR check 워크플로는 build/test 만 한다.

`workflow_dispatch` 로 master 에서 수동 실행하는 경우에도 sub 는 `ref:refs/heads/master` 이므로 정상 assume 된다.
