# ClassTime 유지보수 노트

코드를 읽으면 알 수 있는 내용은 적지 않는다. **왜 그렇게 되어 있는지**와
**아직 하지 않은 것**만 적는다.

## 아키텍처

```
UI (Compose)  ──hiltViewModel()──▶  ViewModel  ──▶  ClassTimeRepository  ──▶  Room DAO
                                                          │
Service / Receiver ──@AndroidEntryPoint──────────────────┘
```

의존성은 전부 Hilt(`di/AppModule.kt`)가 조립한다. `AppDatabase.get()` 같은
수동 싱글턴은 없다. `ClassTimeRepository` 는 `Context` 가 아니라 DAO 를 직접 받으므로
안드로이드 프레임워크 없이 생성할 수 있고, 테스트에서 가짜 DAO 를 끼울 수 있다.

### 건드리기 전에 알아야 할 것

| 파일 | 함부로 바꾸면 안 되는 이유 |
| --- | --- |
| `RecordingService.enterForeground` | `startForeground()` 는 인스턴스당 **한 번만** 부른다. 두 번 부르면 그 시점의 앱 상태로 마이크 권한이 재평가되어, 애써 잡아 둔 권한을 잃고 무음이 녹음된다. |
| `RecordingService` 의 `@Volatile` 4개 | 메인 스레드(`onStartCommand`)와 IO 코루틴이 공유한다. 빼면 "녹음 중인데 알림은 대기 중", "끝난 세션을 살아 있다고 보고 다음 수업을 건너뜀" 같은 간헐적 버그가 난다. |
| `ScheduleManager` 의 재설정/따라잡기 분리 | `rescheduleAll()` 은 절대 녹음을 시작하지 않는다. 합치면 종료 알람이 재설정을 트리거해 방금 끝난 수업이 다시 녹음된다. |
| `proguard-rules.pro` 의 `ExceptionType` 규칙 | enum 상수 이름이 그대로 DB 에 저장된다. R8 이 이름을 바꾸면 기존 행을 읽는 순간 터지고 휴강/보강 기록을 잃는다. |
| `TimeUtils.fileStamp` 의 `Locale.ROOT` | 기본 로캘을 쓰면 일부 로캘에서 숫자가 비-ASCII 로 찍혀 파일명이 깨진다. |
| `RecordingStorage.sanitizeSubject` 의 `"기타"` | **일부러 번역하지 않는다.** 파일 시스템 경로에 들어가는 값이라, 로캘이 바뀌면 같은 과목이 두 폴더로 갈라진다. |

### 로깅

`android.util.Log` 를 직접 쓰지 말고 `util/AppLog.kt` 를 쓴다.

- `d` / `i` — `BuildConfig.DEBUG` 가드라 릴리스에서 R8 이 호출을 지운다. 과목명 같은
  사용자 데이터를 실어도 안전하다.
- `w` / `e` — 릴리스에도 남는다. **사용자 데이터를 싣지 않는다.** 이름 대신 id 를 쓴다.

### 브로드캐스트 리시버

`goAsync()` 를 직접 쓰지 말고 `util/ReceiverWork.kt` 의 `runAsync` 를 쓴다.
시스템이 주는 시간은 약 10초뿐이고, 그 안에 `finish()` 를 못 부르면 프로세스가 ANR 로
죽는다. `runAsync` 는 8초 타임아웃과 공용 스코프로 그 두 가지를 막는다.

## 향후 과제

### 1. 크래시 리포팅 (Play 스토어 배포 시점)

지금은 사용자 기기에서 앱이 죽어도 알 방법이 없다. 공개 배포를 한다면 가장 먼저
채워야 할 공백이다. 붙일 자리는 이미 만들어 두었다 — `AppLog.e` 한 곳만 고치면
호출부 20여 군데를 건드릴 필요가 없다.

```kotlin
// util/AppLog.kt 의 e() 안에서
FirebaseCrashlytics.getInstance().recordException(error ?: Exception(message))
```

필요한 작업: `com.google.firebase:firebase-crashlytics` 의존성, `google-services.json`
추가, `com.google.gms.google-services` 플러그인 적용, R8 mapping.txt 업로드 설정.
`proguard-rules.pro` 에 `-keepattributes SourceFile,LineNumberTable` 은 이미 있어서
난독화된 스택트레이스를 복원할 수 있다.

### 2. AGP / compileSdk 업그레이드 (선행 과제)

**이번 작업에서 실제로 막힌 지점이다.** 현재 AGP 8.5.2 / compileSdk 34 는 2024년 중반
수준이고, 최신 라이브러리들이 이미 거부한다:

- Hilt 2.59+ → AGP 9.0 이상 요구 (그래서 2.57.2 로 내려 씀)
- `hilt-navigation-compose` 1.3.0 → compileSdk 35, 1.4.0 → compileSdk 37 요구
  (그래서 1.2.0 으로 내려 씀)

또한 Google Play 는 신규/업데이트 앱에 최신 targetSdk 를 요구하므로,
**스토어 배포를 하려면 이 업그레이드가 선행되어야 한다.** 업그레이드는
AGP → Gradle → Kotlin → compileSdk/targetSdk 가 한 묶음이라 별도 작업으로 다루는 게 안전하다.

### 3. 계측 테스트 — 완료, 단 로컬에서는 실행 불가

**개발 컨테이너에는 `/dev/kvm` 이 없어 에뮬레이터가 뜨지 않는다.** 그래서 테스트를
두 층으로 나눴다.

| 층 | 위치 | 어디서 도는가 |
| --- | --- | --- |
| JVM (Robolectric) | `app/src/test/` — 97개 | 컨테이너·CI 모두 |
| 계측 (실기기) | `app/src/androidTest/` | CI 의 `instrumentation` 잡에서만 |

Robolectric 쪽이 위험 경로 대부분을 이미 덮는다:

- `ScheduleManagerTest` (14) — 알람이 정확한 시각에 걸리는지, 휴강·학기·보강 반영,
  **재설정이 녹음을 시작하지 않는다**는 회귀 테스트, 놓친 수업 따라잡기.
- `RecordingServiceTest` (6) — 대기 모드 상태 기계. `micReady` 가 틀리면 서비스는
  떠 있는데 녹음만 무음이 되므로 이 값의 전이를 고정한다.
- `AlarmReceiverTest` (4) — 알람 라우팅, 그리고 종료 알람이 재녹음을 유발하지 않는지.

계측 쪽은 JVM 으로는 신뢰할 수 없는 것만 남겼다: Hilt 그래프가 실기기에서 끝까지
조립되는지, 마이그레이션이 **안드로이드 SQLite** 에서 도는지(데스크톱 SQLite 와 다르다),
실제 `AlarmManager` 호출이 권한 정책에 걸려 조용히 실패하지 않는지.

로컬에서 계측 테스트를 돌려야 한다면 KVM 이 있는 호스트에서:
`./gradlew connectedDebugAndroidTest`

### 4. 오류의 사용자 노출

실패를 조용히 삼키던 곳들은 이제 최소한 로그로 남는다(`AppLog.e`). 다만 사용자에게
"왜 안 됐는지" 보여주는 UI 는 아직 없다. 스낵바 등으로 올리려면 `ClassTimeRepository`
에 오류 이벤트 채널을 두고 화면이 구독하는 방식이 자연스럽다.

## CI

- `.github/workflows/ci.yml` — push/PR 마다 테스트 · lint · debug 빌드.
- `.github/workflows/release.yml` — 수동 실행. 서명 후 App Distribution 업로드.

release 워크플로에 필요한 GitHub Secrets:

| Secret | 만드는 법 |
| --- | --- |
| `CLASSTIME_KEYSTORE_BASE64` | `base64 -w0 secrets/classtime-release.jks` |
| `CLASSTIME_KEYSTORE_PASSWORD` | `keystore.properties` 의 `storePassword` |
| `CLASSTIME_KEY_ALIAS` | 보통 `classtime` |
| `FIREBASE_APP_ID` | `firebase apps:list ANDROID` |
| `FIREBASE_TESTERS` | 쉼표로 구분한 테스터 이메일 |
| 인증 (아래 둘 중 **하나**) | |
| `FIREBASE_TOKEN` | `firebase login:ci` 가 출력하는 refresh token |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | 서비스 계정 키 JSON 파일 **내용 전체** (base64 아님) |

### 인증을 따로 등록해야 하는 이유

플러그인이 받아들이는 인증은 세 가지뿐이다(`CredentialsRetriever`):
서비스 계정 JSON, `FIREBASE_TOKEN`, 그리고 Firebase CLI 로그인 캐시.

로컬에서는 세 번째가 동작한다 — `firebase login` 이 개인 refresh token 을
`~/.config/configstore/firebase-tools.json` 에 저장해 두기 때문이다. 하지만 그 파일은
그 기기에만 있고 GitHub 러너에는 브라우저도 없으므로, CI 는 앞의 두 가지 중 하나가 필요하다.

`FIREBASE_TOKEN` 은 한 줄로 끝나지만 **개인 계정에 묶인다**(비밀번호 변경·권한 회수 시 끊김).
서비스 계정은 설정이 번거로운 대신 독립적으로 교체·폐기할 수 있는 기계 신분증이다.
워크플로는 둘 중 등록된 것을 알아서 고른다.

CI 는 `version.properties` 에 커밋된 versionCode 를 그대로 쓴다. 배포 전에
`./gradlew bumpVersionCode` 로 올리고 커밋하는 것이 정해진 순서다. CI 가 임의로
올리면 로컬 `./deploy.sh` 가 올린 값과 충돌한다.
