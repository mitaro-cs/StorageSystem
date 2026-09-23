<script lang="ts">
	import { onMount } from 'svelte';

	let status = $state('проверяем сервер…');

	onMount(async () => {
		const res = await fetch('/api/health');
		status = res.ok ? 'сервер на связи' : `ошибка ${res.status}`;
	});
</script>

<main>
	<h1>groupbase</h1>
	<p>{status}</p>
</main>
