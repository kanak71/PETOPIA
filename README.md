# PETOPIA

### 반려동물 박람회 운영·예약·관리 통합 플랫폼

PETOPIA는 반려동물 박람회 운영 전 과정을 지원하는 웹 플랫폼입니다. 관람객에게는 반려동물 정보를 활용한 맞춤형 부스 추천과 방문 동선을 제공하고, 개인 사업자에게는 부스 신청과 관리 권한을, 운영자가 행사 관련 정보를 관리할 수 있도록 구성한 팀 프로젝트입니다.

> 이 저장소는 6인 팀 프로젝트의 [원본 저장소](https://github.com/PET0PIA/PETOPIA)를 개인 계정으로 Fork한 버전입니다.

## 프로젝트 개요

| 항목 | 내용 |
| --- | --- |
| 개발 기간 | 2026.07.28 ~ 2026.08.26 |
| 개발 인원 | 6명 |
| 프로젝트 유형 | 반려동물 박람회 운영 전 과정을 지원하는 웹 플랫폼  |
| 담당 분야 | 백엔드 — 회원가입/로그인 인증, 반려동물 정보, AI 부스 추천·동선 추천 |
| 원본 저장소 | [PET0PIA/PETOPIA](https://github.com/PET0PIA/PETOPIA) |

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot, MyBatis |
| Database / Cache | MySQL, Redis |
| Frontend (팀) | React |
| External API | Claude API |
| Storage (팀) | AWS S3 |
| Collaboration | Git, GitHub |

## 주요 기능 — 팀 전체

- 회원가입·로그인 및 JWT 기반 인증
- 도면을 통한 박람회 행사·장소 및 참가업체·부스 관리
- Redis 기반 예약 대기열 및 QR 입장 관리
- 결제 승인 및 정산 관리
- 운영 통계 및 관리자 기능
- Claude API 기반 맞춤형 부스 추천 및 관람 동선 생성
- 이메일 인증 및 예약·결제 안내

※ 위 항목은 팀 프로젝트의 주요 서비스 기능입니다.

## 개인 담당 기능

### 1. JWT 인증 및 Refresh Token 관리

- Access Token과 Refresh Token을 구분한 인증 흐름 구현
- Redis를 활용한 Refresh Token 관리 및 재발급 처리
- 동일 Refresh Token에 대한 중복 소비 가능성을 줄이기 위해 `GETDEL` 적용

**처리 흐름**

1. 로그인 이후 Access Token과 Refresh Token 발급
2. 인증이 필요한 API 요청에서 JWT 유효성 검증
3. 토큰 재발급 시 Redis에서 기존 Refresh Token을 조회하면서 삭제
4. 기존 토큰의 소비 결과를 확인한 뒤 재발급 처리

### 2. 반려동물 정보 관리

- 반려동물 정보 등록·조회·수정 기능 구현
- 부스 추천 시 활용할 반려동물 특성 및 요구사항 데이터 연계

### 3. Claude API 기반 맞춤형 부스 추천 및 동선 생성

- 반려동물 정보, 알레르기 및 요구사항을 활용해 추천 조건 구성
- Claude API로 추천 부스 ID와 추천 이유를 수신
- 실제 DB의 부스 목록과 대조해 존재하지 않는 부스 및 중복 추천 결과 제거
- 검증을 통과한 부스의 좌표를 활용해 서버에서 방문 순서 계산

**처리 흐름**

`추천 조건 구성 → Claude API 호출 → DB 기반 결과 검증 → 좌표 기반 방문 순서 계산`

AI가 반환한 값을 그대로 화면에 전달하지 않고, 서버에서 실제 데이터와 대조해 결과를 검증하도록 구성했습니다.

## 문제 해결 및 개선

### 1. Redis `GETDEL`을 활용한 Refresh Token 중복 소비 방지

**문제**

기존 토큰의 조회와 삭제가 분리되면, 동일 Refresh Token을 이용한 요청이 동시에 발생했을 때 여러 요청이 토큰을 사용 가능하다고 판단할 수 있습니다.

**개선**

- Redis `GETDEL`을 사용해 단일 키의 조회와 삭제를 하나의 원자적 명령으로 처리
- 실제 Redis를 사용한 동시성 통합 테스트 추가

**검증**

| 검증 항목 | 결과 |
| --- | --- |
| 테스트 조건 | 동일 토큰에 대한 100건의 동시 소비 시도 × 5회 |
| 각 회차 결과 | 성공 1건 / 나머지 99건 거부 |
| 중복 성공 | 총 0건 |

이 결과는 해당 테스트 조건에서 Redis 기반 토큰 소비 단계의 중복 성공이 관측되지 않았음을 의미합니다.

관련 코드: [`RefreshTokenConcurrencyIntegrationTest.java`](src/test/java/com/ms/petopia/api/auth/service/RefreshTokenConcurrencyIntegrationTest.java)

### 2. AI 추천 결과의 데이터 검증 강화

**문제**

외부 AI API 응답에 실제 행사 데이터에 존재하지 않는 부스 ID나 중복 부스가 포함될 수 있었습니다.

**개선**

- 추천 결과를 DB의 실제 부스 정보와 대조
- 유효하지 않은 부스 ID와 중복 결과 제거
- 추천의 의미적 판단은 AI에 맡기되, 데이터 검증과 방문 순서 계산은 서버에서 처리하도록 역할 구분

**확인 사항**

추천 결과의 존재 여부·중복 여부를 검증했습니다.

관련 코드:

- [`RecommendationService.java`](src/main/java/com/ms/petopia/api/recommendation/service/RecommendationService.java)
- [`BoothRecommendationMapper.xml`](src/main/resources/mapper/recommendation/BoothRecommendationMapper.xml)
- [`RecommendationServiceTest.java`](src/test/java/com/ms/petopia/api/recommendation/service/RecommendationServiceTest.java)

---

## 개인 Fork에서 진행한 후속 개선

팀 프로젝트 종료 후 개인 Fork에서 추천 결과 검증 로직을 보완하고, 인증 동시성 및 추천 서비스 관련 테스트 코드를 추가·수정했습니다.


## 원본 프로젝트

- **팀 원본:** [PET0PIA/PETOPIA](https://github.com/PET0PIA/PETOPIA)
