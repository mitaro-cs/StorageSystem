#!/bin/sh
# Проверка приложения хоста (для CI): запускает собранную оболочку дважды, ждёт, пока встроенный
# сервер ответит, а окно пройдёт вход и откроет сайт без зацикливания. Ловит ошибки, которые видны
# только при настоящем запуске (пути Windows, упаковка Java и jar, service worker в окне).
#
#   scripts/desktop-smoke.sh desktop/src-tauri/target/debug/groupbase-desktop
set -eu

BIN=$1
[ -f "$BIN.exe" ] && BIN="$BIN.exe"
DATA="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/groupbase-smoke-$$"
mkdir -p "$DATA"

start() {
	GROUPBASE_DATA="$DATA" "$BIN" >>"$DATA/shell.out" 2>&1 &
	PID=$!
}

# Останавливаем только то, что запустили сами: на компьютере разработчика может работать
# настоящий groupbase — его трогать нельзя (поиск по имени программы задел бы и его).
stop() {
	if command -v taskkill >/dev/null 2>&1; then
		# Git Bash на Windows: $! — номер процесса MSYS, а taskkill нужен номер Windows.
		winpid=$(cat "/proc/$PID/winpid" 2>/dev/null || true)
		if [ -n "$winpid" ]; then
			taskkill //F //T //PID "$winpid" >/dev/null 2>&1 || true
		elif [ -n "${CI:-}" ]; then
			taskkill //F //T //IM "$(basename "$BIN")" >/dev/null 2>&1 || true
		fi
	else
		pkill -P "$PID" >/dev/null 2>&1 || true
		kill "$PID" >/dev/null 2>&1 || true
	fi
	wait "$PID" 2>/dev/null || true
}

start

fail() {
	echo "Ошибка: $1"
	stop
	for f in "$DATA/shell.out" "$DATA/logs/shell.log" "$DATA/logs/java.log"; do
		echo "== $f"
		cat "$f" 2>/dev/null || true
	done
	exit 1
}

port=""
for _ in $(seq 1 120); do
	p=$(sed -n 's/^port=//p' "$DATA/desktop.properties" 2>/dev/null || true)
	if [ -n "$p" ] && curl -sf -o /dev/null "http://127.0.0.1:$p/api/health"; then
		port=$p
		break
	fi
	sleep 1
done
[ -n "$port" ] || fail "сервер приложения не ответил за 2 минуты"

curl -sf -D - -o /dev/null "http://127.0.0.1:$port/" | grep -qi '^x-groupbase: 1' ||
	fail "главная страница пришла не от сервера groupbase"

# Окно должно пройти вход и открыть сайт (на чистых данных — первичную настройку), а не застрять
# на /api/desktop/enter: так было, пока service worker подменял ответ входа.
opened() {
	for _ in $(seq 1 60); do
		grep -q "127.0.0.1:$port/setup" "$DATA/logs/shell.log" 2>/dev/null && return 0
		sleep 1
	done
	return 1
}
no_loop() {
	sleep 3
	n=$(grep -c "api/desktop/enter" "$DATA/logs/shell.log" 2>/dev/null || true)
	[ "${n:-0}" -le 4 ] || fail "$1: окно зациклилось на входе ($n переходов)"
}

opened || fail "окно приложения не открыло сайт после входа"
no_loop "первый запуск"
stop

# Второй запуск — с тем, что окно сохранило в первый раз (кеш, service worker, cookie).
sleep 3
: >"$DATA/logs/shell.log"
start
opened || fail "при втором запуске окно не открыло сайт"
no_loop "второй запуск"
stop
echo "Приложение запустилось дважды: сервер на порту $port, окно открывает сайт без зацикливания."
