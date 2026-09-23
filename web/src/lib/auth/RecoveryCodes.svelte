<script lang="ts">
	import { Copy, Download, Printer } from '@lucide/svelte';
	import { copy, siteUrl } from '$lib/copy';
	import { session } from '$lib/session.svelte';
	import Button from '$lib/ui/Button.svelte';

	// Резервные коды показываются один раз: даём скачать, скопировать и распечатать,
	// и просим подтвердить, что они сохранены.
	let {
		codes,
		ondone,
		doneLabel = 'Готово'
	}: { codes: string[]; ondone: () => void; doneLabel?: string } = $props();

	let saved = $state(false);

	function asText(): string {
		const who = session.me?.user.username ?? '';
		return [
			'groupbase — резервные коды для входа',
			`Сайт: ${siteUrl()}`,
			who ? `Пользователь: ${who}` : '',
			`Создано: ${new Date().toLocaleString('ru-RU')}`,
			'',
			'Если телефона нет под рукой, введите любой из кодов вместо кода из приложения.',
			'Каждый код работает один раз.',
			'',
			...codes
		]
			.filter((l, i) => l !== '' || i > 3)
			.join('\n');
	}

	function download() {
		const url = URL.createObjectURL(new Blob([asText()], { type: 'text/plain;charset=utf-8' }));
		const a = document.createElement('a');
		a.href = url;
		a.download = 'groupbase-резервные-коды.txt';
		a.click();
		setTimeout(() => URL.revokeObjectURL(url), 1000);
		saved = true;
	}
</script>

<div class="recovery">
	<p class="tip amber">
		<span
			>Если потеряете телефон, войти можно будет только с этими кодами. <strong
				>Каждый код работает один раз.</strong
			> Сохраните их там, где найдёте: в менеджере паролей, в файле или на бумаге.</span
		>
	</p>
	<ol class="codes num" aria-label="Резервные коды">
		{#each codes as c (c)}<li><code>{c}</code></li>{/each}
	</ol>
	<div class="actions no-print">
		<Button onclick={download}><Download size={17} /> Скачать</Button>
		<Button onclick={() => copy(asText(), 'Коды скопированы').then(() => (saved = true))}
			><Copy size={17} /> Копировать</Button
		>
		<Button onclick={() => (window.print(), (saved = true))}
			><Printer size={17} /> Распечатать</Button
		>
	</div>
	<label class="check no-print"
		><input type="checkbox" bind:checked={saved} /> Я сохранил(а) коды в надёжном месте</label
	>
	<div class="no-print">
		<Button variant="primary" disabled={!saved} onclick={ondone}>{doneLabel}</Button>
	</div>
</div>

<style>
	.recovery {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
	}
	.codes {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 8px;
		margin: 0;
		padding: 16px;
		border-radius: var(--r);
		background: var(--surface-2);
		list-style: none;
	}
	.codes code {
		font: 600 16px var(--mono);
		letter-spacing: 0.04em;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	@media print {
		.no-print {
			display: none !important;
		}
	}
</style>
