<script lang="ts">
	import { goto } from '$app/navigation';
	import { Split } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { loadSubjects } from '$lib/data.svelte';
	import { toast } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import FormSection from '$lib/ui/FormSection.svelte';
	import Modal from '$lib/ui/Modal.svelte';

	// «Разделить на подгруппы» (0.9.5): сколько – кнопками, и сразу название каждой. Номер «№N»
	// подставляется сам и остаётся в названии – по нему расписание из файла находит свою подгруппу.
	let { open = $bindable(), subject }: { open: boolean; subject: Subject } = $props();

	const base = $derived(subject.name.replace(/\s*(№\s*\d+|\(\s*\d+\s*\)).*$/u, '').trim());
	let count = $state(2);
	let labels = $state<string[]>(['', '', '', '', '', '']);
	let busy = $state(false);
	let error = $state('');
	const bad = (l: string) => /\d/.test(l);
	const dirty = $derived(labels.some((l) => l.trim()));

	async function save(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		if (labels.slice(0, count).some(bad)) {
			error = 'В названии подгруппы – без цифр: номер подставится сам';
			return;
		}
		busy = true;
		try {
			await post<Subject[]>(`/api/subjects/${subject.id}/subgroups`, {
				count,
				names: labels.slice(0, count).map((l) => l.trim())
			});
			await loadSubjects();
			toast(`Готово: подгрупп – ${count}. Каждый выберет свою`, 'ok');
			open = false;
			goto('/subjects');
		} catch (err) {
			error = err instanceof Error ? err.message : 'Не получилось';
		} finally {
			busy = false;
		}
	}
</script>

<Modal
	bind:open
	{dirty}
	title="Разделить на подгруппы"
	subtitle={base}
	icon={Split}
	tone={subject.color}
>
	<form id="split-form" class="stack form" onsubmit={save}>
		<FormSection title="Сколько подгрупп">
			<div class="counts" role="radiogroup" aria-label="Сколько подгрупп">
				{#each [2, 3, 4, 5, 6] as n (n)}
					<button
						type="button"
						role="radio"
						aria-checked={count === n}
						class:on={count === n}
						onclick={() => (count = n)}>{n}</button
					>
				{/each}
			</div>
		</FormSection>
		<FormSection title="Названия" hint="необязательно">
			{#each Array.from({ length: count }, (_, i) => i) as i (i)}
				<label class="line">
					<span class="num">№{i + 1}</span>
					<input
						class="input"
						class:bad={bad(labels[i])}
						bind:value={labels[i]}
						maxlength="40"
						placeholder={i === 0 ? 'Сильная группа' : i === 1 ? 'Начинающие' : ''}
						aria-label="Название подгруппы №{i + 1}"
					/>
				</label>
			{/each}
			<p class="hint">
				Получится «{base} №1{labels[0].trim() ? ' ' + labels[0].trim() : ''}». Номер остаётся в
				названии – по нему расписание из файла находит свою подгруппу.
			</p>
		</FormSection>
		<p class="note small">
			№1 – этот предмет со всеми заданиями и файлами, остальные появятся пустыми. Пары, у которых в
			расписании указана подгруппа («2 подгр.»), сразу перейдут к своей. Каждый выберет свою
			подгруппу – чужие у него скроются вместе с парами.
		</p>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="split-form" loading={busy}>Разделить</Button>
	{/snippet}
</Modal>

<style>
	.form {
		gap: var(--s3);
	}
	.counts {
		display: flex;
		gap: 8px;
	}
	.counts button {
		width: 48px;
		height: 44px;
		border: 1px solid var(--border);
		border-radius: 12px;
		background: var(--surface);
		color: var(--text-2);
		font: inherit;
		font-size: 16px;
		font-weight: 700;
	}
	.counts button.on {
		border-color: var(--text);
		background: var(--text);
		color: var(--bg);
	}
	.line {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.line .num {
		flex: none;
		width: 36px;
		font-weight: 700;
		color: var(--text-2);
	}
	.line input {
		flex: 1;
		min-width: 0;
	}
	.bad {
		border-color: var(--danger);
	}
	.hint {
		margin: 0;
	}
	.note {
		margin: 0;
		padding: 10px 12px;
		border-radius: var(--r-s);
		background: var(--surface-2);
		color: var(--text-2);
	}
</style>
