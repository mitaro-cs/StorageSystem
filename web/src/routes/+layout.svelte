<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import Toaster from '$lib/ui/Toaster.svelte';
	import { dialog } from '$lib/ui/ask.svelte';

	let { children } = $props();

	// Цветовая тема прежних версий (gb-palette) — один раз переводим в новый «Цвет».
	onMount(() => {
		try {
			if (localStorage.getItem('gb-palette')) import('$lib/colors').then((m) => m.migrateAccent());
		} catch {
			/* приватный режим */
		}
	});

	// Заставка из app.html: когда интерфейс готов — раскрываем приложение кругом из середины. При
	// первом открытии за сессию даём вступлению доиграть (глобус опускается на руки, ~1 с), при
	// перезагрузках не ждём.
	onMount(() => {
		const splash = document.getElementById('splash');
		if (!splash) return;
		let first = true;
		try {
			first = !sessionStorage.getItem('gb-splash');
			sessionStorage.setItem('gb-splash', '1');
		} catch {
			/* приватный режим — считаем первым */
		}
		const wait = first ? Math.max(0, 1000 - performance.now()) : 0;
		setTimeout(() => {
			document.documentElement.classList.add('revealing');
			splash.classList.add('gone');
			setTimeout(() => {
				splash.remove();
				document.documentElement.classList.remove('revealing');
				// Окна «поверх всего» (знакомство) ждут этого: иначе закрыли бы заставку.
				dispatchEvent(new Event('gb:splash-gone'));
			}, 760);
		}, wait);
	});
</script>

{@render children()}
<Toaster />
<!-- Окно подтверждения (ask, askText): код грузится при первом вопросе. -->
{#if dialog.current}
	{#await import('$lib/ui/Dialogs.svelte') then m}<m.default />{/await}
{/if}
