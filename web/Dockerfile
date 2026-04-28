FROM node:20-bookworm AS frontend-build
WORKDIR /app/web/frontend
COPY web/frontend/package.json web/frontend/tsconfig.json web/frontend/vite.config.ts ./
RUN npm install
COPY web/frontend/index.html ./
COPY web/frontend/src ./src
RUN npm run build

FROM python:3.12-slim
WORKDIR /app
COPY converter ./converter
RUN python -m pip install --no-cache-dir -e ./converter
COPY web/backend ./web/backend
RUN python -m pip install --no-cache-dir -e ./web/backend
COPY --from=frontend-build /app/web/frontend/dist ./web/frontend/dist
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
