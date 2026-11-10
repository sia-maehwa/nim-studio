# NIM Studio

NVIDIA NIM(https://build.nvidia.com)을 폰에서 편하게 쓰기 위한 Android 앱입니다.
채팅·이미지 생성·모델 테스트·산출물 라이브러리를 한 곳에서 다룹니다.

## 빌드

이 저장소는 로컬 JDK 없이 **GitHub Actions**에서 APK를 빌드합니다.
`main`에 push하면 자동으로:

1. 단위 테스트 + debug APK 빌드
2. APK를 Actions artifact로 업로드
3. `rolling-latest` 릴리스에 APK 첨부 (폰에서 바로 다운로드용)

폰 설치용 고정 링크:

```
https://github.com/sia-maehwa/nim-studio/releases/download/rolling-latest/app-debug.apk
```

## 구조

```
nim-studio/
├─ .github/workflows/android.yml   CI (테스트 → APK → rolling release)
├─ tools/build-apk.sh              로컬/CI 공용 빌드 스크립트
└─ app/src/main/java/com/siamaehwa/nimstudio/
   ├─ NimStudioApp.kt              Application + 수동 DI(AppContainer)
   ├─ MainActivity.kt
   ├─ core/                        NIM API 클라이언트·보안 저장소·오류 진단
   └─ ui/                          Compose 화면 (채팅·이미지·모델·라이브러리·설정)
```

## 기능 현황

| 기능 | 상태 |
|---|---|
| API 키 관리 (Keystore 암호화 저장, 연결 테스트) | M1 |
| 모델 목록·검색·응답 지연 테스트 | M1 |
| LLM 채팅 (SSE 스트리밍, 코딩 프리셋, 중지) | M1 |
| 이미지 생성 (모델별 파라미터, 갤러리 저장) | M2 예정 |
| 라이브러리 (이미지·코드·산출물 통합 검색) | M2 예정 |
| 대화 영구 저장 (Room) | M2 예정 |
| 서명 release APK (업데이트 유지) | M2 예정 |

## 보안

- API 키는 기기 안에서 Android Keystore(AES-GCM)로 암호화되어 저장됩니다.
- 키는 NIM API 호출 외 어디에도 전송·기록되지 않습니다.
- 저장소에는 어떤 키도 포함되지 않습니다.
