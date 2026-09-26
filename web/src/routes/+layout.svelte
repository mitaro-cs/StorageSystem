<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import Toaster from '$lib/ui/Toaster.svelte';
	import { dialog } from '$lib/ui/ask.svelte';

	let { children } = $props();

	// Заставка из app.html: когда интерфейс готов — плавно убираем. В установленном приложении
	// на телефоне показываем её хотя бы 0,6 с — глобус опускается на руки, пока она тает; в браузере
	// не ждём.
	onMount(() => {
		const splash = document.getElementById('splash');
		if (!splash) return;
		const standalone =
			matchMedia('(display-mode: standalone)').matches ||
			(navigator as Navigator & { standalone?: boolean }).standalone === true;
		const wait = standalone ? Math.max(0, 600 - performance.now()) : 0;
		setTimeout(() => {
			splash.classList.add('gone');
			setTimeout(() => splash.remove(), 400);
		}, wait);
	});
</script>

{@render children()}
<Toaster />
<!-- Окно подтверждения (ask, askText): код грузится при первом вопросе. -->
{#if dialog.current}
	{#await import('$lib/ui/Dialogs.svelte') then m}<m.default />{/await}
{/if}
