const COLORS = [
    '#ef4444', '#f97316', '#eab308', '#22c55e', '#3b82f6', '#8b5cf6'
];

const CHART_DEFAULTS = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: ctx => ` ${ctx.parsed.y.toLocaleString()}` } }
    },
    scales: {
        x: { ticks: { color: '#94a3b8', font: { size: 11 } }, grid: { color: '#1e293b' } },
        y: { ticks: { color: '#94a3b8', font: { size: 11 } }, grid: { color: '#334155' } }
    }
};

function makeBarChart(id, label, data, unit) {
    const ctx = document.getElementById(id);
    if (!ctx || !data || data.length === 0) return;
    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: chartData.labels,
            datasets: [{
                label,
                data,
                backgroundColor: COLORS.slice(0, data.length),
                borderRadius: 6,
            }]
        },
        options: {
            ...CHART_DEFAULTS,
            plugins: {
                ...CHART_DEFAULTS.plugins,
                tooltip: { callbacks: { label: ctx => ` ${ctx.parsed.y.toLocaleString()} ${unit}` } }
            }
        }
    });
}

if (chartData && chartData.labels && chartData.labels.length > 0) {
    makeBarChart('tpsChart', 'TPS', chartData.tps, 'req/s');
    makeBarChart('p99Chart', 'P99', chartData.p99, 'ms');
    makeBarChart('overBookingChart', '오버부킹', chartData.overBooking, '건');
    makeBarChart('errorRateChart', '에러율', chartData.errorRate, '%');
}

function deleteResult(id) {
    if (!confirm(`결과 #${id}을 삭제하시겠습니까?`)) return;
    fetch(`/api/test-results/${id}`, { method: 'DELETE' })
        .then(r => { if (r.ok) location.reload(); else alert('삭제 실패'); })
        .catch(() => alert('삭제 실패'));
}
