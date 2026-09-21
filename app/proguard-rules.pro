# ClassTime R8 규칙.
#
# 원칙: 꼭 필요한 것만 지킨다. 예전에는 `dev.iruki.classtime.data.** { *; }` 한 줄로
# 데이터 계층 전체(리포지토리, DAO, 순수 매칭 로직까지)를 통째로 보호하고 있었는데,
# 그건 난독화와 코드 축소를 사실상 무력화하면서 아래 두 가지 진짜 위험은 막지 못했다.

# --- 크래시 분석 ---
# 난독화된 스택트레이스를 mapping.txt 로 복원하려면 줄 번호가 남아 있어야 한다.
# 원본 파일명은 노출할 이유가 없으므로 단일 이름으로 덮어쓴다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- ExceptionType: 상수 이름이 곧 저장 포맷이다 ---
# Converters 가 `type.name` 을 그대로 DB 에 쓰고 `valueOf(value)` 로 되읽는다.
# R8 이 상수 이름을 바꾸면 업데이트 직후 기존 행을 읽는 순간 IllegalArgumentException 이
# 터지고, 사용자는 휴강/보강 기록을 통째로 잃는다. 여기만은 반드시 지켜야 한다.
-keepclassmembers enum dev.iruki.classtime.data.ExceptionType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# --- Room 엔티티 ---
# Room 이 만들어 내는 코드는 컬럼명을 문자열 리터럴로 들고 있어서 난독화 자체는 안전하다.
# 그럼에도 필드명을 남기는 이유는, 스키마 검증이 어긋났을 때의 결과가 '크래시'가 아니라
# '사용자 데이터 손상'이기 때문이다. 클래스 네 개를 포기하는 값으로 그 위험을 없앤다.
-keepclassmembers class dev.iruki.classtime.data.Course { <fields>; }
-keepclassmembers class dev.iruki.classtime.data.Recording { <fields>; }
-keepclassmembers class dev.iruki.classtime.data.Term { <fields>; }
-keepclassmembers class dev.iruki.classtime.data.ScheduleException { <fields>; }
