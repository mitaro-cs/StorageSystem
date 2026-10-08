<script lang="ts">
	import { Archive } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { loadSubjects, subjects } from '$lib/data.svelte';
	import { plural } from '$lib/format';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import { loadSemesters } from './semesters';

	// Архив семестра (0.9.7): выбранные предметы группы уходят в архив под одним названием.
	let {
		open = $bindable(),
		groupId,
		onsaved
	}: { open: boolean; groupId: number; onsaved?: () => void } = $props();

	let name = $state('');
	let picked = $state<number[]>([]);
	let busy = $state(false);

	// Текущие предметы и архивные без семестра – их можно собрать в архив.
	const choices = $derived(
		subjects.list.filter(
			(s) => s.groups.some((g) => g.id === groupId) && s.can.edit && !(s.archived && s.semester)
		)
	);

	$effect(() => {
		if (!open) return;
		name = '';
		// Уже архивные без семестра – сразу отмечены: их и собирают.
		picked = subjects.list
			.filter((s) => s.archived && !s.semester && s.groups.some((g) => g.id === groupId))
			.map((s) => s.id);
		loadSemesters(groupId)
			.then((r) => (name ||= r.suggested))
			.catch(() => {});
	});

	function toggle(id: number) {
		picked = picked.includes(id) ? picked.filter((x) => x !== id) : [...picked, id];
	}

	async function save() {
		busy = true;
		try {
			await post(`/api/groups/${groupId}/semesters`, { name, subjects: picked });
			await loadSubjects();
			toast(`«${name.trim()}» – в архиве`, 'ok');
			open = false;
			onsaved?.();
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}
</script>

<Modal
	bind:open
	title="Архив семестра"
	icon={Archive}
	subtitle="Задания, материалы, тесты и пары останутся у предметов"
>
	<label class="label" for="as-name">Название</label>
	<input
		id="as-name"
		class="input"
		bind:value={name}
		maxlength="80"
		placeholder="Осенний семестр 2025"
	/>
	<p class="label pick">Предметы этого семестра</p>
	{#if choices.length === 0}
		<p class="hint">Предметов нет.</p>
	{:else}
		<div class="subjects" role="group" aria-label="Предметы этого семестра">
			{#each choices as s (s.id)}
				<label class="subj" class:on={picked.includes(s.id)}>
					<input type="checkbox" checked={picked.includes(s.id)} onchange={() => toggle(s.id)} />
					<SubjectGlyph id={s.id} name={s.name} color={s.color} icon={s.icon} size={30} />
					<span class="txt">
						<strong>{s.name}</strong>
						{#if s.archived}<span class="faint small">уже в архиве, без семестра</span>{/if}
					</span>
				</label>
			{/each}
		</div>
	{/if}
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" loading={busy} disabled={picked.length === 0} onclick={save}>
			<Archive size={16} />
			{picked.length
				? `В архив: ${picked.length} ${plural(picked.length, ['предмет', 'предмета', 'предметов'])}`
				: 'В архив'}
		</Button>
	{/snippet}
</Modal>

<style>
	.pick {
		margin-top: var(--s4);
	}
	.subjects {
		display: flex;
		flex-direction: column;
		gap: 4px;
		max-height: 46vh;
		overflow: auto;
	}
	.subj {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 8px 10px;
		border-radius: 12px;
		cursor: pointer;
	}
	.subj:hover {
		background: var(--surface-2);
	}
	.subj.on {
		background: color-mix(in srgb, var(--accent) 10%, transparent);
	}
	.subj input {
		flex: none;
		width: 18px;
		height: 18px;
		accent-color: var(--accent);
	}
	.txt {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.txt strong {
		font-weight: 600;
		overflow-wrap: anywhere;
	}
</style>
