# 인증·인가

이 문서는 기존 README의 인증 상세 내용을 옮긴 것입니다. 로컬·배포 실행 시 반드시 확인할 환경 변수와 운영 보안 항목은 [README](../README.md) 및 [배포 문서](deployment.md)를 확인하세요.

## 백엔드 인증·인가 (한채희)

이메일 + 비밀번호 로그인, JWT 기반 인증. 소셜 로그인은 쓰지 않는다.

| Method | URI | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | /api/auth/signup | X | 회원가입 (201) |
| POST | /api/auth/login | X | 로그인. Access Token 은 응답 바디, Refresh Token 은 HttpOnly 쿠키 |
| POST | /api/auth/reissue | 쿠키 | Access Token 재발급 (Rotation) |
| POST | /api/auth/logout | O | 로그아웃 (204). 저장된 Refresh Token 삭제 + 쿠키 만료 |
| GET | /api/users/me | O | 내 계정 정보 |

### 토큰 정책

```
Access Token   1시간,  Authorization: Bearer 헤더로 전달
Refresh Token  7일,    HttpOnly 쿠키로 전달, refresh_token 테이블에 저장
```

- 사용자당 유효한 Refresh Token 을 **1개만** 유지한다 (`refresh_token.user_id` UNIQUE). 로그인하면 기존 토큰을 교체한다.
- 재발급은 **2단 검증**이다. JWT 서명·만료·발급자 검증을 통과해도 DB 저장값과 다르면 거부한다.
  그래서 로그아웃했거나 이미 교체된 토큰은 유효기간이 남아 있어도 쓸 수 없다.
- 재발급 시 Refresh Token 도 새로 발급해 교체한다(Rotation). 이전 토큰은 그 즉시 무효가 된다.
- 토큰에 용도(`type`) 클레임을 담아, Refresh Token 으로는 일반 API 에 접근할 수 없다.
- 서명 알고리즘 HS256. `JWT_SECRET` 은 Base64 인코딩된 32바이트 이상 키여야 한다.

### 쿠키 · CORS 설정

환경마다 달라지는 값은 프로파일로 분리했다.

| 설정 | local | prod |
| --- | --- | --- |
| `cookie.secure` | `false` (HTTP) | `true` (HTTPS 필수) |
| `cookie.same-site` | `Lax` | `Lax` — 실제 cross-site 쿠키 전송이 필요한 경우에만 `None; Secure` 검토 |
| `cors.allowed-origins` | `http://localhost:5173` | **배포 주소로 설정 필요** (`application-prod.yml` TODO) |

`HttpOnly` 는 항상 켠다. CORS 는 `SecurityConfig` 의 `CorsConfigurationSource` 한 곳에서만 관리한다.

## 프론트엔드 인증 연동 (2026-10-16 기준)

임시 인증(`X-User-Id` 헤더)은 **백엔드·프론트 모두 JWT 인증으로 교체 완료**되었다.
`CurrentUserArgumentResolver` 가 헤더 대신 `SecurityContext` 에서 사용자를 읽으므로,
`@CurrentUser Long userId` 를 쓰는 컨트롤러는 수정할 필요가 없다.

- 로그인 후 받은 Access Token 을 `Authorization: Bearer {accessToken}` 헤더로 보내면 된다.
- 인증이 필요한 API 를 토큰 없이 호출하면 `401` 과 함께 `{"success":false,"error":{"code":"AUTH_REQUIRED",...}}` 가 내려온다.
- 비로그인 조회 가능: `GET /api/v1/houses/**`, `GET /api/notices/**`

**프론트에서 새 API 를 추가할 때 인증 관련으로 따로 할 일은 없다.** `api/client.ts` 의 공용 `api`
인스턴스를 쓰면 토큰 주입과 만료 시 재발급이 자동으로 처리된다.

| 파일 | 역할 |
| --- | --- |
| `hooks/useAuth.ts` | Access Token(메모리) · 로그인 사용자 상태. `useAuth()` 로 구독 |
| `api/client.ts` | 공용 axios. Bearer 주입 / `withCredentials` / 401 → 재발급 후 1회 재시도 |
| `api/auth.ts` | `login` · `logout` · `bootstrapAuth`. 정식 로그인 화면을 만들 때 그대로 재사용 |

- Access Token 은 **메모리에만** 둔다 (localStorage 저장 안 함). 새로고침 시 `main.tsx` 가
  `bootstrapAuth()` 로 `POST /api/auth/reissue` 를 한 번 호출해 되살린다.
- 여러 요청이 동시에 `401` 이어도 재발급은 **한 번만** 수행하고 나머지는 그 결과를 기다린다
  (Refresh Token Rotation 이라 동시에 재발급하면 서로를 무효화한다).
- `login` · `signup` · `reissue` 는 자동 재발급 대상에서 제외한다. `logout` 은 Access Token 이 필요한
  보호 API 라 일반 요청과 동일하게 재발급 후 재시도한다.
- 헤더의 사용자 1/2 선택 UI(`hooks/useCurrentUser.ts`)는 제거했다. 임시 로그인 입력란으로 대체된다.

인증 API는 위 [백엔드 인증·인가](#백엔드-인증인가-한채희) 참고.
