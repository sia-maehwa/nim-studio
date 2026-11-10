# NIM Studio

NVIDIA NIM(https://build.nvidia.com)을 폰에서 편하게 쓰기 위한 Android 앱입니다.
채팅·이미지 생성·모델 테스트·산출물 라이브러리를 한 곳에서 다룹니다.

## 설치

폰에서 아래 링크를 열면 최신 빌드를 바로 받을 수 있습니다.

```
https://github.com/sia-maehwa/nim-studio/releases/download/rolling-latest/app-release.apk
```

- `app-release.apk`(권장): 서명·R8 최적화된 릴리스. 가볍고 빠릅니다.
- `app-debug.apk`: 디버그 빌드. 로그 확인이 필요할 때만 사용하세요.
- M1(debug 서명)에서 올라오는 경우 서명이 달라 **한 번은 기존 앱을 삭제**해야 합니다.
  이후 M2 release 빌드끼리는 그대로 덮어쓰면 데이터가 유지됩니다.

## 사용

1. 앱 실행 → **설정** 탭 → NVIDIA API 키(`nvapi-...`) 붙여넣기 → "저장하고 연결 테스트".
2. **채팅**: 모델 선택, `일반/코딩` 프리셋, SSE 스트리밍, 답변 복사·라이브러리 저장, 대화 목록.
3. **이미지**: 프롬프트·네거티브·크기·스텝·시드 지정 → 생성(자동으로 라이브러리 저장) → 갤러리/공유.
4. **라이브러리**: 이미지·코드·텍스트 검색, 상세 보기, 공유, 갤러리 내보내기, 삭제.
5. **모델**: 검색·필터·지연 테스트로 쓸 모델을 고릅니다.
6. **에이전트**: 프로젝트 폴더를 한 번 허용하면 파일 목록·읽기·수정·검색을 도구로 수행합니다.
   변경 카드에서 "자세히"(내용 확인)·"되돌리기"가 가능하고, 빌드·실행은 Termux에서 합니다.

## 빌드

이 저장소는 로컬 JDK 없이 **GitHub Actions**에서 APK를 빌드합니다.
`main`에 push하면 자동으로:

1. 단위 테스트 + debug/release APK 빌드 (release는 R8 + 서명)
2. APK를 Actions artifact로 업로드
3. `rolling-latest` 릴리스에 두 APK 첨부

## 구조

```
nim-studio/
├─ .github/workflows/android.yml   CI (테스트 → APK → rolling release)
├─ tools/build-apk.sh              로컬/CI 공용 빌드 스크립트
├─ signing/                        release 서명 키스토어 (PKCS12)
└─ app/src/main/java/com/siamaehwa/nimstudio/
   ├─ NimStudioApp.kt              Application + 수동 DI(AppContainer)
   ├─ MainActivity.kt
   ├─ core/                        NIM API 클라이언트·보안 저장소·오류 진단
   ├─ core/agent/                  SAF 작업 폴더·경로 보호·파일 도구(tool-calling)
   ├─ core/db/                     Room (대화·메시지·라이브러리)
   └─ ui/                          Compose 화면 (채팅·에이전트·이미지·모델·라이브러리·설정)
```

## 기능 현황

| 기능 | 상태 |
|---|---|
| API 키 관리 (Keystore 암호화 저장, 연결 테스트) | M1 |
| 모델 목록·검색·응답 지연 테스트 | M1 |
| LLM 채팅 (SSE 스트리밍, 코딩 프리셋, 중지) | M1 |
| 대화 영구 저장·목록·내보내기 (Room) | M2 |
| 이미지 생성 (크기·스텝·시드, 라이브러리 자동 저장, 갤러리/공유) | M2 |
| 라이브러리 (이미지·코드·텍스트 검색·공유·삭제) | M2 |
| 서명 release APK (R8, 업데이트 시 데이터 유지) | M2 |
| 코딩 에이전트 (SAF 폴더, 파일 읽기/수정/검색, 되돌리기) | M3 |

## 보안

- API 키는 기기 안에서 Android Keystore(AES-GCM)로 암호화되어 저장됩니다.
- 키는 NIM API 호출 외 어디에도 전송·기록되지 않습니다.
- `signing/`의 키스토어는 개인용 앱 업데이트 서명용입니다. 공개 저장소에 포함되어 있으므로
  이 키로 서명된 APK를 신뢰할 때는 저장소 빌드 산출물인지 확인하세요.
- 에이전트는 사용자가 고른 폴더 밖으로 나가지 않으며(`..`·절대 경로 차단),
  `.git`, `build`, `node_modules` 등 보호 폴더는 수정하지 않습니다. 셸 실행 기능은 없습니다.
