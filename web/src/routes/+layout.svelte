<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import Toaster from '$lib/ui/Toaster.svelte';

	let { children } = $props();

	// Заставка из app.html: когда интерфейс готов — плавно убираем. В установленном приложении
	// на телефоне даём стопке логотипа сложиться (до ~1,2 с с запуска), в браузере не ждём.
	onMount(() => {
		const splash = document.getElementById('splash');
		if (!splash) return;
		const standalone =
			matchMedia('(display-mode: standalone)').matches ||
			(navigator as Navigator & { standalone?: boolean }).standalone === true;
		const wait = standalone ? Math.max(0, 1200 - performance.now()) : 0;
		setTimeout(() => {
			splash.classList.add('gone');
			setTimeout(() => splash.remove(), 400);
		}, wait);
	});
</script>

{@render children()}
<Toaster />
