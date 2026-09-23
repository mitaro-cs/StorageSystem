<script lang="ts">
	import { Download } from '@lucide/svelte';

	let { groupId, groupName }: { groupId: number; groupName: string } = $props();
</script>

<section class="card export">
	<h2>Архив группы «{groupName}»</h2>
	<p class="muted">
		Всё в одном ZIP-файле, который открывается без groupbase. Пригодится, чтобы передать дела новому
		старосте, сохранить материалы после выпуска или переехать на другой сервер.
	</p>
	<dl class="kv">
		<div>
			<dt>Участники.csv</dt>
			<dd>список группы — откроется в Excel</dd>
		</div>
		<div>
			<dt>Новости</dt>
			<dd>каждая новость — текстовый файл</dd>
		</div>
		<div>
			<dt>Предметы</dt>
			<dd>задания с файлами и материалы по папкам</dd>
		</div>
		<div>
			<dt>data.json</dt>
			<dd>всё то же для программ</dd>
		</div>
	</dl>
	<!-- download: браузер скачивает файл, а не переходит по ссылке внутри приложения -->
	<a class="download" href="/api/groups/{groupId}/export" download
		><Download size={18} /> Скачать архив</a
	>
	<p class="faint small">
		Если в группе много файлов, архив собирается дольше — дождитесь окончания загрузки.
	</p>
</section>

<style>
	.export {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: var(--s5);
	}
	.kv dd {
		font-weight: 500;
		color: var(--text-2);
	}
	.download {
		align-self: flex-start;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 44px;
		padding: 0 20px;
		border-radius: var(--r-full);
		background: var(--accent);
		color: var(--accent-text);
		font-weight: 580;
	}
	.download:hover {
		background: var(--accent-hover);
		text-decoration: none;
	}
</style>
