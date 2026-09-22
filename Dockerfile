# ---- Stage 1: restore & publish ----
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS dotnet_build_env
WORKDIR /src

# Copy just the project files first so `restore` is cached unless a .csproj changes.
COPY backend/Babyphone.Api/Babyphone.Api.csproj backend/Babyphone.Api/
COPY backend/Babyphone.BusinessLogic/Babyphone.BusinessLogic.csproj backend/Babyphone.BusinessLogic/
COPY backend/Babyphone.Dal/Babyphone.Dal.csproj backend/Babyphone.Dal/
COPY backend/Babyphone.Dtos/Babyphone.Dtos.csproj backend/Babyphone.Dtos/
COPY backend/Babyphone.Entities/Babyphone.Entities.csproj backend/Babyphone.Entities/
COPY backend/Babyphone.Shared/Babyphone.Shared.csproj backend/Babyphone.Shared/
COPY backend/Directory.Build.props backend/
RUN dotnet restore backend/Babyphone.Api/Babyphone.Api.csproj --disable-parallel

COPY . .
RUN dotnet publish backend/Babyphone.Api/Babyphone.Api.csproj -c Release -o /app/out --no-restore

# ---- Stage 2: runtime image ----
FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app

COPY --from=dotnet_build_env /app/out ./

# SQLite and the Data Protection key ring both live here (Data Source=/var/srv/babyphone.db);
# mount a volume, or every redeploy loses the accounts and signs every phone out.
RUN mkdir -p /var/srv

ENV ASPNETCORE_URLS=http://+:5001
EXPOSE 5001

ENTRYPOINT ["dotnet", "Babyphone.Api.dll"]
