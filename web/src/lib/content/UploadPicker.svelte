<script lang="ts">
	import { goto } from '$app/navigation';
	import { sortedSubjects } from '$lib/data.svelte';
	import { can, session } from '$lib/session.svelte';
	import type { Subject } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import MaterialAdd from './MaterialAdd.svelte';

	// «Загрузить файл» с главной и из палитры: сначала предмет, потом то же окно, что во вкладке
	// «Материалы» предмета. Кто не может выкладывать сразу — предлагает на проверку старосте.
	let { open = $bindable() }: { open: boolean } = $props();

	const canUpload = (s: Subject) => s.groups.some((g) => can('upload_materials', g.id));
	const canSuggest = (s: Subject) => s.groups.some((g) => can('suggest_materials', g.id));
	const list = $derived(
		sortedSubjects(session.groupId, true).filter((s) => canUpload(s) || canSuggest(s))
	);

	let picked = $state<Subject | null>(null);
	let adding = $state(false);

	function pick(s: Subject) {
		picked = s;
		open = false;
		adding = true;
	}
</script>

<Modal bind:open title="Загрузить файл">
	<p class="muted small lead">В какой предмет? Файл появится во вкладке «Материалы».</p>
	<div class="subjects">
		{#each list as s (s.id)}
			<button class="subject" onclick={() => pick(s)}>
				<SubjectGlyph name={s.name} color={s.color} icon={s.icon} size={34} />
				<span>{s.name}</span>
			</button>
		{:else}
			<p class="faint">Нет предметов, куда вы можете загружать файлы.</p>
		{/each}
	</div>
</Modal>

{#if picked}
	<MaterialAdd
		bind:open={adding}
		subjectId={picked.id}
		folderId={null}
		suggest={!canUpload(picked)}
		onsaved={() => picked && goto(`/subjects/${picked.id}?tab=materials`)}
	/>
{/if}

<style>
	.lead {
		margin-bottom: var(--s3);
	}
	.subjects {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
		gap: 8px;
	}
	.subject {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 56px;
		padding: 8px 12px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 550;
		text-align: left;
	}
	.subject:hover {
		background: var(--surface-2);
		border-color: var(--border-strong);
	}
	.subject span {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
