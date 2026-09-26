<script lang="ts">
	import { ArrowUpCircle } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { Status } from './types';

	// Найдена новая версия: в окне хоста — кнопка, иначе — как обновиться.
	let { s }: { s: Status } = $props();
	let updating = $state(false);

	async function updateNow() {
		updating = true;
		try {
			// Дальше всё делает приложение: скачает, остановит сервер, установит и откроется заново.
			await post('/api/desktop/update', {});
		} catch (e) {
			updating = false;
			toastError(e);
		}
	}
</script>

{#if s.update && s.canUpdate}
	<div class="card update">
		<ArrowUpCircle size={22} />
		<span>
			<strong>Доступна новая версия {s.update.version}</strong>
			<span class="small muted"
				>Обновится само: сайт будет недоступен около минуты, данные сохранятся.</span
			>
		</span>
		<Button variant="primary" size="s" loading={updating} onclick={updateNow}
			>Обновить сейчас</Button
		>
	</div>
{:else if s.update && s.desktop}
	<!-- Сайт работает в приложении хоста, а страницу открыли не в его окне (например, с телефона). -->
	<div class="card update">
		<ArrowUpCircle size={22} />
		<span>
			<strong>Доступна новая версия {s.update.version}</strong>
			<span class="small muted"
				>Обновить можно одной кнопкой в окне groupbase на компьютере хоста: «Настройки → Версия и
				обновления» или значок groupbase в строке меню (в трее).</span
			>
		</span>
	</div>
{:else if s.update}
	<a class="card update" href={s.update.url} target="_blank" rel="noreferrer">
		<ArrowUpCircle size={22} />
		<span>
			<strong>Доступна новая версия {s.update.version}</strong>
			<span class="small muted"
				>Скачайте groupbase.jar со страницы выпуска и перезапустите сервер — данные сохранятся.</span
			>
		</span>
	</a>
{/if}

<style>
	.update {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: var(--s3);
		color: var(--text);
		text-decoration: none;
		box-shadow: 0 0 0 2px var(--ok);
	}
	.update > span {
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	/* На телефоне кнопка — под текстом, во всю ширину. */
	@media (max-width: 480px) {
		.update {
			flex-wrap: wrap;
		}
		.update > :global(button) {
			width: 100%;
		}
	}
</style>
