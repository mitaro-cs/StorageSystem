// Окно приложения хоста входит через /api/desktop/enter: страница подключает этот скрипт.
// Service worker и копия данных на устройстве окну хоста не нужны — сервер на этом же компьютере.
// Старые версии их регистрировали, и service worker зацикливал вход окна (см. DesktopController).
// Здесь всё это убирается, и окно переходит туда, куда направил сервер.
(function () {
	var me = document.currentScript;
	var next = (me && me.getAttribute('data-next')) || '/';
	// Только адреса этого же сайта.
	if (next.charAt(0) !== '/' || next.charAt(1) === '/' || next.charAt(1) === '\\') next = '/';

	var gone = false;
	function go() {
		if (gone) return;
		gone = true;
		location.replace(next);
	}

	function quietly(task) {
		try {
			return Promise.resolve(task()).catch(function () {});
		} catch {
			return Promise.resolve();
		}
	}

	var jobs = [
		quietly(function () {
			if (!navigator.serviceWorker || !navigator.serviceWorker.getRegistrations) return;
			return navigator.serviceWorker.getRegistrations().then(function (list) {
				return Promise.all(
					list.map(function (r) {
						return r.unregister();
					})
				);
			});
		}),
		quietly(function () {
			if (!window.caches) return;
			return caches.keys().then(function (keys) {
				return Promise.all(
					keys.map(function (k) {
						return caches.delete(k);
					})
				);
			});
		}),
		quietly(function () {
			if (!window.indexedDB || !indexedDB.databases) return;
			return indexedDB.databases().then(function (dbs) {
				return Promise.all(
					dbs
						.filter(function (d) {
							return d.name && d.name.indexOf('groupbase-') === 0;
						})
						.map(function (d) {
							return new Promise(function (done) {
								var r = indexedDB.deleteDatabase(d.name);
								r.onsuccess = r.onerror = r.onblocked = function () {
									done();
								};
							});
						})
				);
			});
		}),
		quietly(function () {
			localStorage.removeItem('gb-last-user');
		})
	];

	Promise.all(jobs).then(go, go);
	// Что-то повисло — уходим всё равно: уборка повторится при следующем входе.
	setTimeout(go, 1500);
})();
